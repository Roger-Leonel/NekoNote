import asyncio
import logging

import httpx

from armazenamento import ArmazemSupabase
from autenticacao import criar_verificador_supabase
from configuracao import Configuracao
from ia.cliente import ClienteIA
from contexto import Contexto
from limite import LimitePorUsuario
from notion.cliente import ClienteNotion
from protecao.limites import Protecao
from notion.servico import ServicoNotion
from ocr.processar import extrair_texto
from seguranca import Cifra

registro = logging.getLogger("nekonote.inicializacao")


# apaga a conta de login no Supabase Auth (rota de administracao, so com a chave de servico)
def _remover_usuario(config: Configuracao, http: httpx.AsyncClient):
    async def remover(id_usuario: str) -> None:
        resp = await http.delete(
            f"{config.auth_url.rstrip('/')}/admin/users/{id_usuario}",
            headers={"Authorization": f"Bearer {config.supabase_chave_servico}", "apikey": config.supabase_chave_servico},
        )
        # 404 = a conta ja nao existe; nao e erro
        if resp.status_code not in (200, 204, 404):
            resp.raise_for_status()

    return remover


# se faltar variavel de ambiente devolve None
def criar_contexto(config: Configuracao, http: httpx.AsyncClient) -> Contexto | None:
    faltando = config.faltando_para_producao()
    if faltando:
        registro.warning("Servidor sem configuração completa; rotas da API ficam em 503. Faltam: %s", ", ".join(faltando))
        return None

    fracos = config.segredos_fracos()
    if fracos:
        registro.warning("Segredos curtos demais (use 32+ caracteres aleatórios): %s", ", ".join(fracos))

    armazem = ArmazemSupabase(http, config.supabase_url, config.supabase_chave_servico)
    cifra = Cifra(config.chave_criptografia)
    notion = ServicoNotion(config, armazem, cifra, ClienteNotion(config, http))
    return Contexto(
        config=config,
        armazem=armazem,
        cifra=cifra,
        verificador=criar_verificador_supabase(config.jwt_secret),
        limite=LimitePorUsuario(config.limite_processamentos_por_hora),
        notion=notion,
        ocr=extrair_texto,
        ia=ClienteIA(config, http),
        remover_usuario=_remover_usuario(config, http),
        protecao=Protecao(config),
        http=http,
    )
