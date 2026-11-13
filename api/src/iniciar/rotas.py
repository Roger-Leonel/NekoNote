import asyncio
import json

from fastapi import APIRouter, Depends, WebSocket

from autenticacao import TokenInvalido, extrair_bearer
from contexto import Contexto, obter_contexto
from ocr.iniciar import processar
from ocr.pedido import ErroPedido, validar_pedido

router = APIRouter()

CODIGO_NAO_AUTORIZADO = 1008
# pings e mensagens invalidas que a conexao aguenta antes de mandar o pedido
MAX_MENSAGENS_ANTES_DO_PEDIDO = 20


async def _recusar(websocket: WebSocket, mensagem: str, codigo: int = 1000) -> None:
    await websocket.send_json({"tipo": "erro", "status": "error", "mensagem": mensagem})
    await websocket.close(code=codigo)


# O login ja foi conferido pelo middleware antes do handshake (cabecalho Authorization). Aqui so se usa o resultado.
@router.websocket("/ws/ocr")
async def websocket_ocr(websocket: WebSocket, ctx: Contexto = Depends(obter_contexto)):
    usuario_conexao = getattr(websocket.state, "usuario", None)
    if usuario_conexao is None:
        await websocket.close(code=CODIGO_NAO_AUTORIZADO)
        return
    token_conexao = extrair_bearer(websocket.headers.get("authorization"))

    await websocket.accept()
    recebidas = 0
    while True:
        try:
            bruto = await asyncio.wait_for(websocket.receive(), ctx.config.ws_espera_segundos)
        except asyncio.TimeoutError:
            await _recusar(websocket, "Tempo esgotado esperando o pedido.")
            return
        if bruto["type"] == "websocket.disconnect":
            return

        recebidas += 1
        if recebidas > MAX_MENSAGENS_ANTES_DO_PEDIDO:
            await _recusar(websocket, "Mensagens demais antes do pedido.", CODIGO_NAO_AUTORIZADO)
            return
        try:
            mensagem = json.loads(bruto.get("text") or "")
        except json.JSONDecodeError:
            mensagem = None
        if not isinstance(mensagem, dict):
            await websocket.send_json({"tipo": "erro", "mensagem": "Mensagem inválida."})
            continue

        acao = mensagem.get("acao")
        if acao == "ping":
            await websocket.send_json({"tipo": "pong", "mensagem": "Conexão ativa"})
            continue
        if acao != "processar":
            # nao devolve o que o cliente mandou
            await websocket.send_json({"tipo": "erro", "mensagem": "Ação desconhecida."})
            continue

        # a conexao pode ter ficado aberta alguns instantes: confere de novo o token (o da mensagem, se vier)
        token = mensagem.get("id_token") or token_conexao
        if not isinstance(token, str):
            await _recusar(websocket, "Autenticação necessária.", CODIGO_NAO_AUTORIZADO)
            return
        try:
            usuario = await ctx.verificador(token)
        except TokenInvalido:
            await _recusar(websocket, "Sessão inválida ou expirada.", CODIGO_NAO_AUTORIZADO)
            return
        if usuario.id != usuario_conexao.id:
            await _recusar(websocket, "Sessão inválida.", CODIGO_NAO_AUTORIZADO)
            return

        try:
            pedido = validar_pedido(mensagem, ctx.config)
        except ErroPedido as erro:
            await _recusar(websocket, str(erro))
            return

        tarefa = ctx.tarefas.obter(usuario.id, pedido.id_pedido)
        if tarefa is not None:
            # reconexao do mesmo envio: reaproveita o que ja rodou (nada de gastar IA nem criar pagina em dobro)
            if tarefa.assinatura != pedido.assinatura:
                await _recusar(websocket, "Este pedido já foi usado com outro conteúdo.")
                return
        else:
            # OCR e CPU: um processamento por usuario e um teto para o servidor todo
            if not ctx.protecao.simultaneos.entrar(usuario.id):
                await _recusar(websocket, "Já há um processamento em andamento. Aguarde terminar e tente de novo.")
                return
            tarefa = ctx.tarefas.iniciar(
                usuario.id, pedido.id_pedido, pedido.assinatura,
                lambda emitir: processar(ctx, usuario, pedido, emitir),
                ao_terminar=lambda: ctx.protecao.simultaneos.sair(usuario.id),
            )
        await tarefa.assistir(websocket.send_json)
        try:
            await websocket.close()
        except RuntimeError:
            pass  # ja fechada pelo cliente
        return
