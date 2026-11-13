import logging
import secrets
from html import escape

from fastapi import APIRouter, Depends
from fastapi.responses import HTMLResponse

from autenticacao import Usuario, usuario_atual
from contexto import Contexto, obter_contexto
from erros import ErroApi
from esquemas import OperacaoResposta, PaginasResposta, UrlAutorizacaoResposta
from notion.cliente import ErroNotion
from notion.servico import NaoConectado, PrecisaReconectar, StateInvalido

router = APIRouter()
logger = logging.getLogger("nekonote.notion")

def erro_do_notion(erro: Exception) -> ErroApi:
    """Traduz falhas do Notion no erro da API. `status` (reconectar/desconectado) e o que o app ja entende."""
    if isinstance(erro, PrecisaReconectar):
        return ErroApi(409, "NOTION_RECONECTAR", "A conexão com o Notion expirou. Reconecte para continuar.", extras={"status": "reconectar"})
    if isinstance(erro, NaoConectado):
        return ErroApi(409, "NOTION_DESCONECTADO", "Conecte o Notion para continuar.", extras={"status": "desconectado"})
    if isinstance(erro, ErroNotion) and erro.status == 429:
        return ErroApi(503, "NOTION_LIMITANDO", "O Notion está limitando as requisições. Tente de novo em instantes.")
    return ErroApi(502, "NOTION_INDISPONIVEL", "Não foi possível falar com o Notion agora.")


@router.post("/notion/conectar", response_model=UrlAutorizacaoResposta)
async def conectar(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    if not ctx.config.notion_configurado:
        raise ErroApi(503, "NOTION_NAO_CONFIGURADO", "Integração com o Notion não configurada no servidor.")
    return {"url_autorizacao": ctx.notion.url_autorizacao(usuario.id)}


# o notion chama aqui depois que o usuario autoriza
@router.get("/notion/callback", response_class=HTMLResponse, include_in_schema=False)
async def retorno_oauth(
    code: str | None = None,
    state: str | None = None,
    error: str | None = None,
    ctx: Contexto = Depends(obter_contexto),
):
    if error:
        return _pagina_retorno("cancelado" if error == "access_denied" else "erro")
    if not code or not state:
        return _pagina_retorno("erro")
    try:
        await ctx.notion.concluir_conexao(code, state)
    except StateInvalido:
        return _pagina_retorno("erro")
    except ErroNotion:
        return _pagina_retorno("erro")
    except Exception:
        # ex.: banco indisponivel. O usuario ve a pagina de erro em vez de um 500 cru
        logger.exception("falha ao concluir a conexao com o notion")
        return _pagina_retorno("erro")
    return _pagina_retorno("ok")


@router.get("/notion/paginas", response_model=PaginasResposta)
async def paginas(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    try:
        return {"paginas": await ctx.notion.listar_paginas(usuario.id)}
    except (PrecisaReconectar, NaoConectado, ErroNotion) as erro:
        raise erro_do_notion(erro) from erro


@router.delete("/notion/conexao", response_model=OperacaoResposta)
async def desconectar(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    await ctx.notion.desconectar(usuario.id)
    return {"status": "desconectado"}

_MENSAGENS = {
    "ok": ("Notion conectado!", "Pode voltar ao NekoNote."),
    "cancelado": ("Autorização cancelada", "Você pode tentar de novo no app."),
    "erro": ("Não foi possível conectar", "Volte ao app e tente novamente."),
}


def _pagina_retorno(status: str) -> HTMLResponse:
    titulo, texto = _MENSAGENS[status]
    link = f"nekonote://notion?status={status}"
    nonce = secrets.token_urlsafe(16)
    html = f"""<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>NekoNote</title>
<style nonce="{nonce}">body{{font-family:system-ui,sans-serif;background:#ff6b14;color:#fff;margin:0;min-height:100vh;display:flex;
align-items:center;justify-content:center;text-align:center;padding:24px}}h1{{margin:0 0 8px}}p{{margin:0 0 24px}}
a{{display:inline-block;background:#231f20;color:#fff;text-decoration:none;font-weight:700;padding:14px 28px;border-radius:16px}}</style>
</head><body><main><h1>{escape(titulo)} 🐾</h1><p>{escape(texto)}</p>
<a href="{link}">Voltar ao app</a></main>
<script nonce="{nonce}">setTimeout(function(){{window.location.href="{link}";}},300);</script></body></html>"""
    csp = (f"default-src 'none'; style-src 'nonce-{nonce}'; script-src 'nonce-{nonce}'; "
           "base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
    return HTMLResponse(html, headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
                                       "Content-Security-Policy": csp})
