import asyncio

import httpx
import pytest

from tests.conftest import auth, conectar_notion


@pytest.mark.parametrize("metodo,rota", [("get", "/conta"), ("delete", "/conta"), ("post", "/notion/conectar"),
                                         ("get", "/notion/paginas"), ("delete", "/notion/conexao"), ("get", "/notas")])
def test_rotas_exigem_token(cliente, metodo, rota):
    assert getattr(cliente, metodo)(rota).status_code == 401
    assert getattr(cliente, metodo)(rota, headers=auth("token-falso")).status_code == 401


def test_conta_desconectada_e_depois_conectada(cliente):
    conta = cliente.get("/conta", headers=auth()).json()
    assert conta["email"] == "ana@exemplo.com" and conta["notion"] == {"status": "desconectado"}

    conectar_notion(cliente)
    notion = cliente.get("/conta", headers=auth()).json()["notion"]
    assert notion["status"] == "conectado" and notion["espaco_nome"] == "Espaço da Ana"
    assert notion["paginas"] == 2
    assert "token" not in str(cliente.get("/conta", headers=auth()).json()).lower()


def test_conectar_gera_url_com_state_do_usuario(cliente):
    url = httpx.URL(cliente.post("/notion/conectar", headers=auth()).json()["url_autorizacao"])
    assert url.host == "api.notion.com" and url.path == "/v1/oauth/authorize"
    assert url.params["client_id"] == "client-id" and url.params["owner"] == "user"
    assert url.params["redirect_uri"] == "https://api.teste/notion/callback"


def test_callback_grava_tokens_cifrados(cliente, ctx):
    conectar_notion(cliente)
    doc = asyncio.run(ctx.armazem.obter("uid-ana"))
    assert doc["token_acesso"] != "acesso-1" and doc["token_renovacao"] != "renovacao-1"
    assert ctx.cifra.decifrar(doc["token_acesso"]) == "acesso-1"
    assert "acesso-1" not in str(doc)


def test_callback_rejeita_state_invalido_cancelamento_e_codigo_ruim(cliente, ctx):
    assert "status=erro" in cliente.get("/notion/callback", params={"code": "codigo-bom", "state": "lixo"}).text
    assert "status=cancelado" in cliente.get("/notion/callback", params={"error": "access_denied"}).text
    assert "status=erro" in cliente.get("/notion/callback", params={"error": "server_error"}).text
    assert "status=erro" in cliente.get("/notion/callback").text
    state = httpx.URL(cliente.post("/notion/conectar", headers=auth()).json()["url_autorizacao"]).params["state"]
    assert "status=erro" in cliente.get("/notion/callback", params={"code": "codigo-ruim", "state": state}).text
    assert asyncio.run(ctx.armazem.obter("uid-ana")) is None


def test_paginas_liberadas_sem_notas_neko(cliente):
    conectar_notion(cliente)
    paginas = cliente.get("/notion/paginas", headers=auth()).json()["paginas"]
    assert [p["titulo"] for p in paginas] == ["Home", "Faculdade"]
    assert paginas[1]["icone"] == "🎓"
    # a hierarquia: Faculdade e filha de Home; Home e de topo
    assert paginas[0]["pai"] is None and paginas[1]["pai"] == "pag-home"


def test_paginas_em_hierarquia_sem_bancos_nem_linhas(cliente, notion_falso):
    def pagina(id_, titulo, pai):
        return {"id": id_, "object": "page", "icon": None, "archived": False, "parent": pai,
                "properties": {"title": {"type": "title", "title": [{"plain_text": titulo}]}}}

    # Home > Faculdade ; pagina dentro de blocos aninhados da Faculdade ; linhas de uma tabela/calendario
    # (e paginas dentro delas) nao entram ; bloco ilegivel vira pagina de topo
    notion_falso.paginas = [
        pagina("pag-home", "Home", {"type": "workspace", "workspace": True}),
        pagina("pag-faculdade", "Faculdade", {"type": "page_id", "page_id": "pag-home"}),
        pagina("linha-1", "Entregar TCC", {"type": "database_id", "database_id": "db-tarefas"}),
        pagina("filha-da-linha", "Anotacoes", {"type": "page_id", "page_id": "linha-1"}),
        pagina("neta-da-linha", "Rascunho", {"type": "block_id", "block_id": "blk-da-linha"}),
        pagina("pag-no-bloco", "Resumo", {"type": "block_id", "block_id": "blk-coluna"}),
        pagina("pag-sem-acesso", "Perdida", {"type": "block_id", "block_id": "blk-inexistente"}),
    ]
    notion_falso.pais_de_blocos = {
        "blk-coluna": {"type": "block_id", "block_id": "blk-toggle"},
        "blk-toggle": {"type": "page_id", "page_id": "pag-faculdade"},
        "blk-da-linha": {"type": "page_id", "page_id": "linha-1"},
    }
    conectar_notion(cliente)
    itens = {p["id"]: p for p in cliente.get("/notion/paginas", headers=auth()).json()["paginas"]}
    assert set(itens) == {"pag-home", "pag-faculdade", "pag-no-bloco", "pag-sem-acesso"}
    assert itens["pag-home"]["pai"] is None
    assert itens["pag-faculdade"]["pai"] == "pag-home"
    # a pagina dentro de blocos aninhados sobe ate a pagina dona
    assert itens["pag-no-bloco"]["pai"] == "pag-faculdade"
    # bloco que nao da pra ler: vira pagina de topo, sem quebrar a lista
    assert itens["pag-sem-acesso"]["pai"] is None
    assert cliente.get("/conta", headers=auth()).json()["notion"]["paginas"] == 4


def test_paginas_sem_conexao_pede_conectar(cliente):
    resp = cliente.get("/notion/paginas", headers=auth())
    assert resp.status_code == 409 and resp.json()["status"] == "desconectado"


def test_notas_so_com_icone_neko_mais_recente_primeiro(cliente):
    conectar_notion(cliente)
    notas = cliente.get("/notas", headers=auth()).json()["notas"]
    assert [n["titulo"] for n in notas] == ["Redes", "VBA"]
    vba = notas[1]
    assert vba["subtitulo"] == "Curso de VBA para Excel." and vba["topicos"] == ["Funções", "Métodos"]
    assert vba["url"] == "https://www.notion.so/nota-vba" and vba["data"].startswith("2024-02-17")


def test_usuarios_sao_isolados(cliente):
    conectar_notion(cliente, "token-ana")
    resp = cliente.get("/notion/paginas", headers=auth("token-bia"))
    assert resp.status_code == 409


def test_renova_token_quando_notion_responde_401(cliente, ctx, notion_falso):
    conectar_notion(cliente)
    notion_falso.tokens_validos = {"outro"}
    assert cliente.get("/notion/paginas", headers=auth()).status_code == 200
    assert notion_falso.chamadas_refresh == 1
    doc = asyncio.run(ctx.armazem.obter("uid-ana"))
    assert ctx.cifra.decifrar(doc["token_acesso"]) == "acesso-2"
    assert ctx.cifra.decifrar(doc["token_renovacao"]) == "renovacao-2"


def test_renovacoes_simultaneas_usam_uma_unica_chamada(cliente, ctx, notion_falso):
    conectar_notion(cliente)
    notion_falso.tokens_validos = {"outro"}

    async def rodar():
        return await asyncio.gather(*(ctx.notion.token_valido("uid-ana") for _ in range(5)))

    tokens = asyncio.run(rodar())
    assert set(tokens) == {"acesso-2"}
    assert notion_falso.chamadas_refresh == 1


def test_falha_na_renovacao_marca_reconectar(cliente, ctx, notion_falso):
    conectar_notion(cliente)
    notion_falso.tokens_validos = {"outro"}
    notion_falso.refresh_aceito = "revogado"
    resp = cliente.get("/notion/paginas", headers=auth())
    assert resp.status_code == 409 and resp.json()["status"] == "reconectar"
    assert cliente.get("/conta", headers=auth()).json()["notion"]["status"] == "reconectar"
    conectar_notion(cliente)
    notion_falso.tokens_validos = {"acesso-1"}
    assert cliente.get("/notion/paginas", headers=auth()).status_code == 200


def test_desconectar_apaga_tokens(cliente, ctx):
    conectar_notion(cliente)
    assert cliente.delete("/notion/conexao", headers=auth()).status_code == 200
    assert asyncio.run(ctx.armazem.obter("uid-ana")) is None
    assert cliente.get("/conta", headers=auth()).json()["notion"] == {"status": "desconectado"}


def test_cadastro_so_conta_depois_de_finalizar(cliente, ctx):
    # sem notion conectado nao da pra finalizar
    resp = cliente.post("/conta/finalizar", headers=auth())
    assert resp.status_code == 409 and resp.json()["status"] == "desconectado"
    assert cliente.get("/conta", headers=auth()).json()["cadastro_concluido"] is False

    conectar_notion(cliente)
    assert cliente.get("/conta", headers=auth()).json()["cadastro_concluido"] is False
    assert cliente.post("/conta/finalizar", headers=auth()).json() == {"status": "ok"}
    assert cliente.get("/conta", headers=auth()).json()["cadastro_concluido"] is True
    # reconectar o notion depois nao desfaz o cadastro
    conectar_notion(cliente)
    assert cliente.get("/conta", headers=auth()).json()["cadastro_concluido"] is True


def test_desconectar_depois_de_finalizar_mantem_a_conta(cliente, ctx):
    conectar_notion(cliente)
    cliente.post("/conta/finalizar", headers=auth())
    assert cliente.delete("/notion/conexao", headers=auth()).status_code == 200
    conta = cliente.get("/conta", headers=auth()).json()
    # o Notion sai, mas o cadastro continua concluido (senao a faxina apagaria a conta)
    assert conta["notion"]["status"] == "desconectado" and conta["cadastro_concluido"] is True
    doc = asyncio.run(ctx.armazem.obter("uid-ana"))
    assert doc["token_acesso"] is None and doc["token_renovacao"] is None and doc["cadastro_concluido"] is True
    # sem Notion nao da pra listar paginas, e conectar de novo volta ao normal
    assert cliente.get("/notion/paginas", headers=auth()).status_code == 409
    conectar_notion(cliente)
    assert cliente.get("/conta", headers=auth()).json()["notion"]["status"] == "conectado"


def test_finalizar_exige_token(cliente):
    assert cliente.post("/conta/finalizar").status_code == 401


def test_excluir_conta_apaga_tokens_e_usuario_de_login(cliente, ctx):
    conectar_notion(cliente)
    assert cliente.delete("/conta", headers=auth()).status_code == 200
    assert asyncio.run(ctx.armazem.obter("uid-ana")) is None and ctx.apagados == ["uid-ana"]


def test_servidor_sem_configuracao_responde_503():
    from contexto import definir_contexto
    from fastapi.testclient import TestClient
    from iniciar.app import app

    definir_contexto(None)
    c = TestClient(app, base_url="http://localhost")
    assert c.get("/conta", headers=auth()).status_code == 503
    assert c.get("/privacidade").status_code == 200
    assert c.get("/saude").json() == {"status": "ok"}
