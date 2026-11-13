from fastapi import APIRouter, Depends

from autenticacao import Usuario, usuario_atual
from contexto import Contexto, obter_contexto
from esquemas import ContaResposta, OperacaoResposta
from notion.rotas import erro_do_notion
from notion.servico import NaoConectado, PrecisaReconectar
from notion.cliente import ErroNotion

router = APIRouter()


# mesmo calculo que o app faz
def id_publico(id_usuario: str) -> str:
    h = 1125899906842597
    for c in id_usuario:
        h = (31 * h + ord(c)) & 0xFFFFFFFFFFFFFFFF
    return str(h & 0x7FFFFFFFFFFFFFFF).zfill(11)[:11]


@router.get("/conta", response_model=ContaResposta, response_model_exclude_unset=True)
async def conta(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    return {
        "email": usuario.email,
        "id_publico": id_publico(usuario.id),
        "notion": await ctx.notion.status(usuario.id),
        "cadastro_concluido": await ctx.notion.cadastro_concluido(usuario.id),
    }


@router.post("/conta/finalizar", response_model=OperacaoResposta)
async def finalizar_cadastro(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    try:
        await ctx.notion.finalizar_cadastro(usuario.id)
    except (PrecisaReconectar, NaoConectado, ErroNotion) as erro:
        raise erro_do_notion(erro) from erro
    return {"status": "ok"}


@router.delete("/conta", response_model=OperacaoResposta)
async def excluir_conta(usuario: Usuario = Depends(usuario_atual), ctx: Contexto = Depends(obter_contexto)):
    await ctx.notion.desconectar(usuario.id)
    await ctx.remover_usuario(usuario.id)
    return {"status": "excluida"}
