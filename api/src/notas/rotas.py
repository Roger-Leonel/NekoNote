from fastapi import APIRouter, Depends

from autenticacao import Usuario, usuario_atual
from contexto import Contexto, obter_contexto
from esquemas import NotasResposta
from notion.cliente import ErroNotion
from notion.rotas import erro_do_notion
from notion.servico import NaoConectado, PrecisaReconectar

router = APIRouter()


@router.get("/notas", response_model=NotasResposta)
async def notas(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    try:
        lista = await ctx.notion.listar_notas(usuario.id)
    except (PrecisaReconectar, NaoConectado, ErroNotion) as erro:
        raise erro_do_notion(erro) from erro
    return {"notas": sorted(lista, key=lambda n: n["data"], reverse=True)}
