import asyncio

import pytest

from ocr.pedido import ErroPedido, validar_pedido
from configuracao import Configuracao
from ocr.tarefas import Tarefas
from tests.conftest import auth, conectar_notion
from tests.test_websocket import pedido, receber_ate_fim

FINAL = {"tipo": "resultado", "mensagem": "pronto"}


async def _coletar(tarefa):
    recebidos = []

    async def enviar(evento):
        recebidos.append(evento)

    await tarefa.assistir(enviar)
    return recebidos


def test_conexao_que_cai_e_volta_recebe_o_historico_e_o_final_sem_refazer():
    execucoes = []

    async def cenario():
        tarefas = Tarefas(graca=60)
        liberar = asyncio.Event()

        async def trabalho(emitir):
            execucoes.append(1)
            await emitir({"tipo": "inicio"})
            await liberar.wait()
            return FINAL

        tarefa = tarefas.iniciar("u1", "pedido-123", "assin", trabalho, ao_terminar=lambda: None)
        await asyncio.sleep(0)

        # primeira conexao ve o inicio e cai
        async def cai(evento):
            raise ConnectionError("cliente saiu")

        await tarefa.assistir(cai)
        assert not tarefa.concluida

        # reconecta: o mesmo pedido encontra a tarefa e se encaixa
        assert tarefas.obter("u1", "pedido-123") is tarefa
        espera = asyncio.create_task(_coletar(tarefa))
        await asyncio.sleep(0)
        liberar.set()
        recebidos = await espera
        assert [e["tipo"] for e in recebidos] == ["inicio", "resultado"]

        # chegou depois de acabar: devolve o resultado guardado
        assert [e["tipo"] for e in await _coletar(tarefas.obter("u1", "pedido-123"))] == ["inicio", "resultado"]
        # outro usuario com o mesmo id nao enxerga a tarefa
        assert tarefas.obter("u2", "pedido-123") is None

    asyncio.run(cenario())
    assert execucoes == [1]


def test_tarefa_sem_ninguem_acompanhando_e_cancelada_depois_da_graca():
    liberados = []

    async def cenario():
        tarefas = Tarefas(graca=0.05)

        async def trabalho(emitir):
            await emitir({"tipo": "inicio"})
            await asyncio.sleep(30)
            return FINAL

        tarefa = tarefas.iniciar("u1", "pedido-123", "assin", trabalho, ao_terminar=lambda: liberados.append(1))
        await asyncio.sleep(0)

        async def cai(evento):
            raise ConnectionError("saiu")

        await tarefa.assistir(cai)
        await asyncio.sleep(0.2)
        assert tarefa.task.cancelled() and liberados == [1]
        # cancelada: o app pode pedir de novo com o mesmo id
        assert tarefas.obter("u1", "pedido-123") is None

    asyncio.run(cenario())


def test_reconexao_dentro_da_graca_cancela_o_cancelamento():
    async def cenario():
        tarefas = Tarefas(graca=0.1)
        liberar = asyncio.Event()

        async def trabalho(emitir):
            await emitir({"tipo": "inicio"})
            await liberar.wait()
            return FINAL

        tarefa = tarefas.iniciar("u1", "pedido-123", "assin", trabalho, ao_terminar=lambda: None)
        await asyncio.sleep(0)

        async def cai(evento):
            raise ConnectionError("saiu")

        await tarefa.assistir(cai)
        espera = asyncio.create_task(_coletar(tarefa))
        await asyncio.sleep(0.25)  # passou da graca, mas tinha alguem acompanhando
        assert not tarefa.task.done()
        liberar.set()
        assert (await espera)[-1]["tipo"] == "resultado"

    asyncio.run(cenario())


def test_falha_inesperada_vira_erro_para_o_cliente_e_libera_a_vaga():
    liberados = []

    async def cenario():
        tarefas = Tarefas()

        async def trabalho(emitir):
            raise RuntimeError("detalhe interno: senha=123")

        tarefa = tarefas.iniciar("u1", "pedido-123", "assin", trabalho, ao_terminar=lambda: liberados.append(1))
        eventos = await _coletar(tarefa)
        assert eventos[-1]["tipo"] == "erro" and "senha" not in str(eventos)

    asyncio.run(cenario())
    assert liberados == [1]


def test_resultado_guardado_expira_depois_do_ttl():
    async def cenario():
        tarefas = Tarefas(ttl=0.05)

        async def trabalho(emitir):
            return FINAL

        tarefa = tarefas.iniciar("u1", "pedido-123", "assin", trabalho, ao_terminar=lambda: None)
        await _coletar(tarefa)
        assert tarefas.obter("u1", "pedido-123") is tarefa
        await asyncio.sleep(0.1)
        assert tarefas.obter("u1", "pedido-123") is None

    asyncio.run(cenario())


# ---------- pelo websocket ----------

def test_mesmo_pedido_reenviado_devolve_o_resultado_sem_gastar_ia_nem_criar_pagina_de_novo(cliente, ia_falsa, notion_falso, ctx):
    conectar_notion(cliente)
    envio = pedido(id_pedido="envio-abc-12345")
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(envio)
        primeira = receber_ate_fim(ws)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(envio)
        segunda = receber_ate_fim(ws)

    assert primeira == segunda and segunda[-1]["tipo"] == "resultado"
    assert len(ia_falsa.chamadas) == 1 and len(notion_falso.paginas_criadas) == 1
    # a quota por hora conta o pedido, nao a reconexao
    assert ctx.limite.tentar("uid-ana") and ctx.limite.tentar("uid-ana") and not ctx.limite.tentar("uid-ana")


def test_novo_id_de_pedido_processa_de_novo(cliente, ia_falsa):
    conectar_notion(cliente)
    for id_pedido in ("envio-aaaaaaaa", "envio-bbbbbbbb"):
        with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
            ws.send_json(pedido(id_pedido=id_pedido))
            receber_ate_fim(ws)
    assert len(ia_falsa.chamadas) == 2


def test_mesmo_id_com_conteudo_diferente_e_recusado(cliente, ia_falsa):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(id_pedido="envio-abc-12345"))
        receber_ate_fim(ws)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(id_pedido="envio-abc-12345", id_pagina="pag-faculdade"))
        eventos = receber_ate_fim(ws)
    assert "outro conteúdo" in eventos[-1]["mensagem"] and len(ia_falsa.chamadas) == 1


def test_id_de_pedido_de_outro_usuario_nao_reaproveita_resultado(cliente, ia_falsa):
    conectar_notion(cliente, "token-ana")
    conectar_notion(cliente, "token-bia")
    for token in ("token-ana", "token-bia"):
        with cliente.websocket_connect("/ws/ocr", headers=auth(token)) as ws:
            ws.send_json(pedido(id_pedido="envio-abc-12345", id_token=token))
            assert receber_ate_fim(ws)[-1]["tipo"] == "resultado"
    assert len(ia_falsa.chamadas) == 2


@pytest.mark.parametrize("ruim", ["curto", "x" * 65, "com espaco aqui", "../../etc"])
def test_id_de_pedido_invalido_e_recusado(ruim):
    base = {"titulo": "T", "id_pagina": "p", "modalidades": {"topicos": True}, "imagens": ["YQ=="]}
    with pytest.raises(ErroPedido):
        validar_pedido({**base, "id_pedido": ruim}, Configuracao())
    # sem id (app antigo) gera um novo a cada envio
    a, b = validar_pedido(base, Configuracao()), validar_pedido(base, Configuracao())
    assert a.id_pedido != b.id_pedido and a.assinatura == b.assinatura
