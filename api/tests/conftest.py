import base64
import json
import os

# o TestClient do websocket sempre usa o host "testserver"; precisa estar na lista antes de o app ser criado
os.environ.setdefault("API_DOMAIN", "testserver")

import httpx
import pytest
from fastapi.testclient import TestClient

from armazenamento import ArmazemMemoria
from ia.esquema import Assunto, NotaGerada, Slide, Topico
from autenticacao import TokenInvalido, Usuario
from configuracao import Configuracao
from contexto import Contexto, definir_contexto
from iniciar.app import app
from limite import LimitePorUsuario
from notion.cliente import ClienteNotion
from notion.servico import ServicoNotion
from protecao.limites import Protecao
from seguranca import Cifra, gerar_chave_criptografia

USUARIOS = {"token-ana": Usuario("uid-ana", "ana@exemplo.com"), "token-bia": Usuario("uid-bia", "bia@exemplo.com")}


# notion de mentira, so pros testes
class NotionFalso:

    def __init__(self):
        self.tokens_validos = {"acesso-1"}
        self.refresh_aceito = "renovacao-1"
        self.novo_par = ("acesso-2", "renovacao-2")
        self.chamadas_refresh = 0
        self.paginas = [
            {"id": "pag-home", "object": "page", "icon": None, "archived": False,
             "properties": {"title": {"type": "title", "title": [{"plain_text": "Home"}]}}},
            {"id": "pag-faculdade", "object": "page", "icon": {"type": "emoji", "emoji": "🎓"}, "archived": False,
             "parent": {"type": "page_id", "page_id": "pag-home"},
             "properties": {"Name": {"type": "title", "title": [{"plain_text": "Faculdade"}]}}},
            {"id": "nota-vba", "object": "page", "icon": {"type": "emoji", "emoji": "📊"}, "archived": False,
             "created_by": {"object": "user", "id": "bot-usuario-1"},
             "created_time": "2024-02-17T10:00:00.000Z", "url": "https://www.notion.so/nota-vba",
             "properties": {"title": {"type": "title", "title": [{"plain_text": "VBA"}]}}},
            {"id": "nota-redes", "object": "page", "icon": {"type": "emoji", "emoji": "🌐"}, "archived": False,
             "created_by": {"object": "user", "id": "bot-usuario-1"},
             "created_time": "2024-03-02T10:00:00.000Z", "url": "https://www.notion.so/nota-redes",
             "properties": {"title": {"type": "title", "title": [{"plain_text": "Redes"}]}}},
        ]
        self.blocos = {
            "nota-vba": [
                {"type": "heading_2", "heading_2": {"rich_text": [{"plain_text": "Resumo"}]}},
                {"type": "paragraph", "paragraph": {"rich_text": [{"plain_text": "Curso de VBA para Excel."}]}},
                {"type": "heading_2", "heading_2": {"rich_text": [{"plain_text": "Tópicos"}]}},
                {"type": "bulleted_list_item", "bulleted_list_item": {"rich_text": [{"plain_text": "Funções"}]}},
                {"type": "bulleted_list_item", "bulleted_list_item": {"rich_text": [{"plain_text": "Métodos"}]}},
            ],
        }
        self.paginas_inacessiveis: set[str] = set()
        self.paginas_criadas: list[dict] = []
        self.blocos_anexados: list[dict] = []
        self.falha_ao_criar: int | None = None
        self.pais_de_blocos: dict[str, dict] = {}

    def handler(self, req: httpx.Request) -> httpx.Response:
        caminho = req.url.path
        if caminho == "/v1/oauth/token":
            corpo = json.loads(req.content)
            if corpo["grant_type"] == "authorization_code":
                if corpo["code"] != "codigo-bom":
                    return httpx.Response(400, json={"error": "invalid_grant"})
                return httpx.Response(200, json={
                    "access_token": "acesso-1", "refresh_token": "renovacao-1", "bot_id": "bot-1",
                    "workspace_id": "esp-1", "workspace_name": "Espaço da Ana", "workspace_icon": "🐱"})
            self.chamadas_refresh += 1
            if corpo["refresh_token"] != self.refresh_aceito:
                return httpx.Response(400, json={"error": "invalid_grant"})
            self.tokens_validos = {self.novo_par[0]}
            self.refresh_aceito = self.novo_par[1]
            return httpx.Response(200, json={"access_token": self.novo_par[0], "refresh_token": self.novo_par[1]})

        token = req.headers.get("Authorization", "").removeprefix("Bearer ")
        if token not in self.tokens_validos:
            return httpx.Response(401, json={"code": "unauthorized", "message": "API token is invalid."})
        if req.method == "POST" and caminho == "/v1/pages":
            if self.falha_ao_criar:
                return httpx.Response(self.falha_ao_criar, json={"code": "validation_error", "message": "recusado"})
            self.paginas_criadas.append({"corpo": json.loads(req.content), "token": token})
            return httpx.Response(200, json={"id": "nova-pagina", "url": "https://www.notion.so/nova-pagina"})
        if req.method == "PATCH" and caminho.endswith("/children"):
            self.blocos_anexados.append({"id": caminho.split("/")[3], "corpo": json.loads(req.content), "token": token})
            return httpx.Response(200, json={"results": []})
        if caminho == "/v1/users/me":
            return httpx.Response(200, json={"object": "user", "id": "bot-usuario-1"})
        if caminho == "/v1/search":
            return httpx.Response(200, json={"results": self.paginas})
        if caminho.startswith("/v1/blocks/") and not caminho.endswith("/children"):
            id_bloco = caminho.rsplit("/", 1)[-1]
            if id_bloco not in self.pais_de_blocos:
                return httpx.Response(404, json={"code": "object_not_found", "message": "nope"})
            return httpx.Response(200, json={"id": id_bloco, "parent": self.pais_de_blocos[id_bloco]})
        if caminho.startswith("/v1/pages/"):
            id_pagina = caminho.rsplit("/", 1)[-1]
            if id_pagina in self.paginas_inacessiveis:
                return httpx.Response(404, json={"code": "object_not_found", "message": "nope"})
            return httpx.Response(200, json={"id": id_pagina})
        if caminho.startswith("/v1/blocks/") and caminho.endswith("/children"):
            id_bloco = caminho.split("/")[3]
            return httpx.Response(200, json={"results": self.blocos.get(id_bloco, [])})
        return httpx.Response(404, json={"code": "not_found"})


class IaFalsa:
    def __init__(self):
        self.chamadas: list[dict] = []
        self.erro: Exception | None = None
        self.nota = NotaGerada(
            titulo="Curso de VBA", emoji="📊",
            resumo="Resumo da IA.\n\nSegundo parágrafo.",
            assuntos=[Assunto(titulo="Funções", conteudo="Sobre funções.")],
            topicos=[Topico(titulo="Métodos", subtopicos=["Range", "Cells"])],
            apresentacao=[Slide(titulo="Introdução", pontos=["Ponto A"], fala="Bem-vindos.")],
        )

    async def gerar_nota(self, instrucoes, texto_usuario):
        self.chamadas.append({"instrucoes": instrucoes, "texto": texto_usuario})
        if self.erro:
            raise self.erro
        return self.nota


@pytest.fixture
def notion_falso():
    return NotionFalso()


@pytest.fixture
def ia_falsa():
    return IaFalsa()


@pytest.fixture
def ctx(notion_falso, ia_falsa):
    config = Configuracao(
        notion_client_id="client-id", notion_client_secret="client-secret", segredo_state="segredo-state",
        chave_criptografia=gerar_chave_criptografia(), notion_redirect_uri="https://api.teste/notion/callback",
        cache_notion_segundos=0, limite_processamentos_por_hora=3,
    )
    armazem = ArmazemMemoria()
    cifra = Cifra(config.chave_criptografia)
    http = httpx.AsyncClient(transport=httpx.MockTransport(notion_falso.handler))
    notion = ServicoNotion(config, armazem, cifra, ClienteNotion(config, http))

    async def verificador(token: str) -> Usuario:
        if token in USUARIOS:
            return USUARIOS[token]
        raise TokenInvalido("token inválido")

    async def ocr(dados: bytes, cfg) -> str:
        return dados.decode().strip()

    apagados: list[str] = []

    async def remover_usuario(uid: str) -> None:
        apagados.append(uid)

    contexto = Contexto(config=config, armazem=armazem, cifra=cifra, verificador=verificador,
                        limite=LimitePorUsuario(config.limite_processamentos_por_hora), notion=notion,
                        ocr=ocr, ia=ia_falsa, remover_usuario=remover_usuario,
                        protecao=Protecao(config))
    contexto.apagados = apagados
    definir_contexto(contexto)
    yield contexto
    definir_contexto(None)


@pytest.fixture
def cliente(ctx):
    return TestClient(app, base_url="http://localhost")


def auth(token: str = "token-ana") -> dict:
    return {"Authorization": f"Bearer {token}"}


def imagem(texto: str) -> str:
    return base64.b64encode(texto.encode()).decode()


def conectar_notion(cliente, token: str = "token-ana") -> None:
    url = cliente.post("/notion/conectar", headers=auth(token)).json()["url_autorizacao"]
    state = httpx.URL(url).params["state"]
    resp = cliente.get("/notion/callback", params={"code": "codigo-bom", "state": state})
    assert "status=ok" in resp.text
