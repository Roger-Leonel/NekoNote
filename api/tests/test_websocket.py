import pytest
from starlette.websockets import WebSocketDisconnect

from ia.erros import ErroIA
from ia.esquema import NotaGerada, Topico
from tests.conftest import auth, conectar_notion, imagem


def pedido(**extra) -> dict:
    base = {"acao": "processar", "id_token": "token-ana", "titulo": "Curso VBA 2024", "id_pagina": "pag-home",
            "modalidades": {"topicos": True, "resumo": True, "assunto": False},
            "imagens": [imagem("pagina um"), imagem("pagina dois")]}
    return {**base, **extra}


def receber_ate_fim(ws) -> list[dict]:
    eventos = []
    while True:
        try:
            evento = ws.receive_json()
        except WebSocketDisconnect:
            return eventos
        eventos.append(evento)


def test_fluxo_completo_ocr_ia_e_pagina_no_notion(cliente, ia_falsa, notion_falso):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(token="TOKEN-DO-CLIENTE-DEVE-SER-IGNORADO"))
        eventos = receber_ate_fim(ws)

    assert [e["tipo"] for e in eventos] == ["inicio", "progresso", "progresso", "enviando", "criando", "resultado"]
    assert [e["percentual"] for e in eventos if e["tipo"] == "progresso"] == [45, 90]
    assert eventos[-1]["url_pagina"] == "https://www.notion.so/nova-pagina"
    assert eventos[-1]["mensagem"] == "Página criada com sucesso!"

    # todos os textos vao numa unica chamada a IA, na ordem das fotos
    [chamada] = ia_falsa.chamadas
    assert "--- Página 1 ---\npagina um" in chamada["texto"] and "--- Página 2 ---\npagina dois" in chamada["texto"]
    assert chamada["texto"].index("pagina um") < chamada["texto"].index("pagina dois")

    # a pagina nasce na pagina escolhida, com o token do Notion do proprio usuario (nunca o que o cliente mandar)
    [criada] = notion_falso.paginas_criadas
    assert criada["token"] == "acesso-1"
    assert criada["corpo"]["parent"] == {"type": "page_id", "page_id": "pag-home"}
    assert criada["corpo"]["icon"]["emoji"] == "📊"  # escolhido pela IA
    assert criada["corpo"]["properties"]["title"]["title"][0]["text"]["content"] == "Curso de VBA"  # idem
    titulos = [b["heading_2"]["rich_text"][0]["text"]["content"] for b in criada["corpo"]["children"] if b["type"] == "heading_2"]
    assert titulos == ["Resumo", "Tópicos"]  # assunto estava desligado no pedido
    assert not notion_falso.blocos_anexados


def test_apresentacao_e_temperamento_chegam_a_ia(cliente, ia_falsa, notion_falso):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(
            modalidades={"topicos": False, "resumo": False, "assunto": False, "apresentacao": True},
            temperamento="divertido",
        ))
        eventos = receber_ate_fim(ws)

    assert eventos[-1]["tipo"] == "resultado"
    instrucoes = ia_falsa.chamadas[0]["instrucoes"]
    assert '"apresentacao": ligado' in instrucoes and '"resumo": desligado' in instrucoes and "bem-humorado" in instrucoes
    textos = [b[b["type"]]["rich_text"][0]["text"]["content"] for b in notion_falso.paginas_criadas[0]["corpo"]["children"]]
    assert textos == ["Apresentação", "Slide 1: Introdução", "Ponto A", "Bem-vindos."]


def test_temperamento_desconhecido_vira_o_padrao(cliente, ia_falsa):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(temperamento="agressivo"))
        receber_ate_fim(ws)
    assert "Tom claro e natural" in ia_falsa.chamadas[0]["instrucoes"]


def test_sem_autenticacao_e_token_invalido(cliente, ctx):
    # o login e exigido ja no handshake: sem cabecalho ou com token ruim a conexao nem abre
    for cabecalhos in ({}, auth("token-falso"), {"Authorization": "Basic abc"}):
        with pytest.raises(WebSocketDisconnect):
            with cliente.websocket_connect("/ws/ocr", headers=cabecalhos):
                pass

    # token da mensagem invalido (ex.: expirou com a conexao aberta): recusa e fecha
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(id_token="token-falso"))
        assert ws.receive_json()["tipo"] == "erro"


def test_token_da_mensagem_de_outro_usuario_e_recusado(cliente):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth("token-ana")) as ws:
        ws.send_json(pedido(id_token="token-bia"))
        assert ws.receive_json()["tipo"] == "erro"


def test_ping_pong(cliente):
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json({"acao": "ping"})
        assert ws.receive_json()["tipo"] == "pong"


@pytest.mark.parametrize("alteracao", [
    {"modalidades": {"topicos": False, "resumo": False, "assunto": False}},
    {"imagens": []}, {"imagens": [imagem("x")] * 21}, {"id_pagina": ""},
])
def test_pedido_invalido_nao_chega_a_ia(cliente, ia_falsa, alteracao):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(**alteracao))
        eventos = receber_ate_fim(ws)
    assert eventos[-1]["tipo"] == "erro" and not ia_falsa.chamadas


def test_sem_notion_conectado(cliente, ia_falsa):
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        assert "Conecte o Notion" in receber_ate_fim(ws)[-1]["mensagem"]
    assert not ia_falsa.chamadas


def test_pagina_fora_do_acesso_liberado(cliente, ia_falsa, notion_falso):
    conectar_notion(cliente)
    notion_falso.paginas_inacessiveis.add("pag-home")
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        eventos = receber_ate_fim(ws)
    assert eventos[-1]["tipo"] == "erro" and "liberada" in eventos[-1]["mensagem"]
    assert [e["tipo"] for e in eventos] == ["erro"]
    assert not ia_falsa.chamadas


def test_conexao_revogada_pede_reconectar(cliente, notion_falso):
    conectar_notion(cliente)
    notion_falso.tokens_validos = set()
    notion_falso.refresh_aceito = "revogado"
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        assert "expirou" in receber_ate_fim(ws)[-1]["mensagem"]


def test_imagens_em_branco(cliente, ia_falsa):
    conectar_notion(cliente)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(imagens=[imagem("   "), imagem("  \n ")]))
        eventos = receber_ate_fim(ws)
    assert eventos[-1]["tipo"] == "erro" and "reconhecer texto" in eventos[-1]["mensagem"]
    assert not ia_falsa.chamadas


def test_falha_da_ia_vira_erro_e_nao_cria_pagina(cliente, ia_falsa, notion_falso):
    conectar_notion(cliente)
    ia_falsa.erro = ErroIA("O serviço de resumos está muito ocupado. Tente novamente em instantes.")
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        eventos = receber_ate_fim(ws)
    assert eventos[-1] == {"tipo": "erro", "status": "error", "mensagem": "O serviço de resumos está muito ocupado. Tente novamente em instantes."}
    assert not notion_falso.paginas_criadas


def test_ia_sem_conteudo_para_o_que_foi_pedido(cliente, ia_falsa, notion_falso):
    conectar_notion(cliente)
    ia_falsa.nota = NotaGerada()
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        eventos = receber_ate_fim(ws)
    assert "não gerou conteúdo" in eventos[-1]["mensagem"] and not notion_falso.paginas_criadas


@pytest.mark.parametrize("status,trecho", [(404, "não está liberada"), (400, "Não foi possível criar a página")])
def test_notion_recusa_a_criacao(cliente, notion_falso, status, trecho):
    conectar_notion(cliente)
    notion_falso.falha_ao_criar = status
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        eventos = receber_ate_fim(ws)
    assert eventos[-1]["tipo"] == "erro" and trecho in eventos[-1]["mensagem"]


def test_nota_grande_vai_em_lotes_de_100_blocos(cliente, ia_falsa, notion_falso):
    conectar_notion(cliente)
    ia_falsa.nota = NotaGerada(resumo="a" * 4500, topicos=[Topico(titulo=f"T{i}") for i in range(150)])
    notion_falso.paginas_criadas.clear()
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido(modalidades={"topicos": True, "resumo": True, "assunto": False}))
        eventos = receber_ate_fim(ws)
    assert eventos[-1]["tipo"] == "resultado"
    [criada] = notion_falso.paginas_criadas
    [resto] = notion_falso.blocos_anexados
    assert len(criada["corpo"]["children"]) == 100 and resto["id"] == "nova-pagina"
    assert len(criada["corpo"]["children"]) + len(resto["corpo"]["children"]) == 2 + 1 + 150  # 2 de resumo + titulo + 150 topicos... + titulo


def test_limite_de_processamentos_por_hora(cliente, ia_falsa):
    conectar_notion(cliente)
    ultimos = []
    for _ in range(4):
        with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
            ws.send_json(pedido())
            ultimos = receber_ate_fim(ws)
    assert len(ia_falsa.chamadas) == 3
    assert "Limite" in ultimos[-1]["mensagem"]
