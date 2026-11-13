import pytest

from conta.rotas import id_publico
from limite import LimitePorUsuario
from notion.leitura import nota_para_app, titulo_da_pagina
from ocr.pedido import ErroPedido, validar_pedido
from configuracao import Configuracao
from seguranca import Cifra, criar_state, gerar_chave_criptografia, validar_state


def test_cifra_ida_e_volta_e_chave_errada():
    cifra = Cifra(gerar_chave_criptografia())
    token = cifra.cifrar("secret_abc")
    assert token != "secret_abc" and cifra.decifrar(token) == "secret_abc"
    with pytest.raises(ValueError):
        Cifra(gerar_chave_criptografia()).decifrar(token)


def test_state_valido_expirado_e_adulterado():
    state = criar_state("seg", "uid-ana", 600, agora=1000)
    assert validar_state("seg", state, agora=1100) == "uid-ana"
    assert validar_state("seg", state, agora=2000) is None
    assert validar_state("outro-segredo", state, agora=1100) is None
    corpo, _, assinatura = state.rpartition(".")
    assert validar_state("seg", f"{corpo}x.{assinatura}", agora=1100) is None
    assert validar_state("seg", "lixo", agora=1100) is None


def test_limite_por_usuario_janela_deslizante():
    limite = LimitePorUsuario(2, janela_segundos=100)
    assert limite.tentar("a", agora=0) and limite.tentar("a", agora=1)
    assert not limite.tentar("a", agora=2)
    assert limite.tentar("b", agora=2)
    assert limite.tentar("a", agora=101)


def test_id_publico_tem_11_digitos_e_e_estavel():
    assert id_publico("uid-ana") == id_publico("uid-ana")
    assert len(id_publico("uid-ana")) == 11 and id_publico("uid-ana").isdigit()


def test_nota_para_app_monta_card():
    pagina = {"id": "n1", "created_time": "2024-02-17T10:00:00Z", "url": "https://n/1",
              "properties": {"x": {"type": "title", "title": [{"plain_text": "VBA"}]}}}
    blocos = [
        {"type": "heading_2", "heading_2": {"rich_text": [{"plain_text": "Resumo"}]}},
        {"type": "paragraph", "paragraph": {"rich_text": [{"plain_text": "Curso de VBA."}]}},
        {"type": "heading_2", "heading_2": {"rich_text": [{"plain_text": "Tópicos"}]}},
        *[{"type": "bulleted_list_item", "bulleted_list_item": {"rich_text": [{"plain_text": t}]}} for t in ("A", "B", "C", "D")],
    ]
    nota = nota_para_app(pagina, blocos)
    assert nota["titulo"] == "VBA" and nota["subtitulo"] == "Curso de VBA."
    assert nota["topicos"] == ["A", "B", "C"]
    assert titulo_da_pagina({"properties": {}}) == "Sem título"


def test_nota_sem_resumo_usa_assunto_como_subtitulo():
    blocos = [
        {"type": "heading_2", "heading_2": {"rich_text": [{"plain_text": "Assuntos"}]}},
        {"type": "heading_3", "heading_3": {"rich_text": [{"plain_text": "Revolução Industrial"}]}},
    ]
    assert nota_para_app({"id": "n"}, blocos)["subtitulo"] == "Revolução Industrial"


def test_validar_pedido():
    config = Configuracao()
    base = {"titulo": "T", "id_pagina": "p", "modalidades": {"topicos": True}, "imagens": ["YQ=="]}
    assert validar_pedido(base, config).imagens == [b"a"]
    for alteracao in (
        {"id_pagina": ""}, {"modalidades": {}},
        {"modalidades": {"resumo": False}}, {"imagens": []}, {"imagens": ["@@@"]}, {"imagens": ["YQ=="] * 21},
    ):
        with pytest.raises(ErroPedido):
            validar_pedido({**base, **alteracao}, config)


def test_max_images_vem_do_ambiente_e_valida(monkeypatch):
    from configuracao import Configuracao
    monkeypatch.delenv("MAX_IMAGES", raising=False)
    assert Configuracao().max_imagens == 20
    for valor, esperado in (("5", 5), ("0", 20), ("-3", 20), ("abc", 20)):
        monkeypatch.setenv("MAX_IMAGES", valor)
        assert Configuracao().max_imagens == esperado


def test_pedido_acima_do_limite_configurado_e_recusado(monkeypatch):
    import base64
    import pytest
    from configuracao import Configuracao
    from ocr.pedido import ErroPedido, validar_pedido
    monkeypatch.setenv("MAX_IMAGES", "2")
    img = base64.b64encode(b"x").decode()
    msg = {"titulo": "t", "id_pagina": "abc", "modalidades": {"resumo": True}, "imagens": [img] * 3}
    with pytest.raises(ErroPedido, match="no máximo 2"):
        validar_pedido(msg, Configuracao())
    msg["imagens"] = [img] * 2
    assert len(validar_pedido(msg, Configuracao()).imagens) == 2


def test_dominio_gera_redirect_e_hosts(monkeypatch):
    from configuracao import Configuracao
    monkeypatch.setenv("API_DOMAIN", "api.exemplo.com")
    cfg = Configuracao()
    assert cfg.notion_redirect_uri == "https://api.exemplo.com/notion/callback"
    assert "api.exemplo.com" in cfg.hosts_permitidos
