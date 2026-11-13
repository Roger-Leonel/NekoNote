import asyncio
import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from protecao.middlewares import instalar as instalar_protecoes
from configuracao import Configuracao
from contexto import definir_contexto
from autenticacao_proxy import router as router_auth
from conta.rotas import router as router_conta
from erros import instalar_tratadores
from iniciar.rotas import router
from inicializacao import criar_contexto
from limpeza import faxina_periodica
from ocr.processar import verificar_ocr
from notas.rotas import router as router_notas
from notion.rotas import router as router_notion
from paginas.rotas import router as router_paginas

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def ciclo_de_vida(app: FastAPI):
    async with httpx.AsyncClient(timeout=30, follow_redirects=False) as http:
        config = Configuracao()
        ctx = criar_contexto(config, http)
        definir_contexto(ctx)
        faxina = None
        if ctx is not None:
            await asyncio.to_thread(verificar_ocr, config)
            faxina = asyncio.create_task(faxina_periodica(config, http, ctx.armazem, ctx.remover_usuario))
        yield
        if faxina is not None:
            faxina.cancel()
    definir_contexto(None)


def criar_app(config: Configuracao | None = None) -> FastAPI:
    config = config or Configuracao()
    # sem /docs, /redoc nem /openapi.json: a API nao publica o proprio mapa
    app = FastAPI(title="NekoNote API", lifespan=ciclo_de_vida, docs_url=None, redoc_url=None, openapi_url=None)
    instalar_tratadores(app)
    instalar_protecoes(app, config)

    app.include_router(router)
    app.include_router(router_conta)
    app.include_router(router_notion)
    app.include_router(router_notas)
    app.include_router(router_paginas)
    app.include_router(router_auth)

    @app.get("/saude", include_in_schema=False)
    async def saude():
        return {"status": "ok"}

    return app


app = criar_app()
