import httpx
import pytest
from starlette.websockets import WebSocketDisconnect

from configuracao import Configuracao
from protecao.limites import Limitador, Politica, Protecao, Simultaneos, ip_do_cliente, parse_redes
from protecao.rotas_publicas import eh_publica
from tests.conftest import auth


# ---------- limitador ----------

def test_limitador_bloqueia_no_teto_e_libera_com_o_tempo():
    lim = Limitador(3, 60)
    assert all(lim.tentar("a", agora=10).permitido for _ in range(3))
    negado = lim.tentar("a", agora=11)
    assert not negado.permitido and negado.restante == 0 and negado.esperar >= 1
    assert lim.tentar("b", agora=11).permitido  # outra chave nao e afetada
    assert lim.tentar("a", agora=10 + 60 * 2).permitido  # duas janelas depois zerou


def test_limitador_retry_after_e_honesto():
    lim = Limitador(5, 60)
    for _ in range(5):
        lim.tentar("a", agora=61)
    negado = lim.tentar("a", agora=70)
    # esperar o que ele pediu tem que liberar
    assert lim.tentar("a", agora=70 + negado.esperar).permitido


def test_limitador_consultar_nao_conta():
    lim = Limitador(2, 60)
    for _ in range(10):
        assert lim.consultar("a", agora=1).permitido
    lim.tentar("a", agora=1)
    lim.tentar("a", agora=1)
    assert not lim.consultar("a", agora=2).permitido


def test_limitador_memoria_limitada_por_numero_de_chaves():
    lim = Limitador(1, 60, max_chaves=100)
    for i in range(1000):
        lim.tentar(f"ip-{i}", agora=1)
    assert len(lim._estado) <= 100


def test_simultaneos_por_usuario_e_total():
    s = Simultaneos(por_usuario=1, total=2)
    assert s.entrar("a") and not s.entrar("a")
    assert s.entrar("b") and not s.entrar("c")
    s.sair("a")
    assert s.entrar("c")


# ---------- ip e rotas publicas ----------

def _escopo(peer, **cabecalhos):
    return {"client": (peer, 1234), "headers": [(k.lower().replace("_", "-").encode(), v.encode()) for k, v in cabecalhos.items()]}


def test_cf_connecting_ip_so_vale_de_proxy_confiavel():
    redes = parse_redes(None)
    assert ip_do_cliente(_escopo("127.0.0.1", cf_connecting_ip="203.0.113.7"), redes) == "203.0.113.7"
    # de fora da rede confiavel o cabecalho e ignorado: ninguem escolhe o proprio IP
    assert ip_do_cliente(_escopo("198.51.100.9", cf_connecting_ip="203.0.113.7"), redes) == "198.51.100.9"
    # lixo no cabecalho nao vira chave
    assert ip_do_cliente(_escopo("127.0.0.1", cf_connecting_ip="<script>"), redes) == "127.0.0.1"


def test_ipv6_conta_pelo_prefixo_64():
    redes = parse_redes(None)
    a = ip_do_cliente(_escopo("127.0.0.1", cf_connecting_ip="2001:db8:1:2::1"), redes)
    b = ip_do_cliente(_escopo("127.0.0.1", cf_connecting_ip="2001:db8:1:2:ffff::9"), redes)
    assert a == b


def test_rotas_publicas_sao_so_as_listadas():
    for metodo, caminho in [("GET", "/"), ("GET", "/saude"), ("GET", "/notion/callback"), ("POST", "/auth/v1/token")]:
        assert eh_publica(metodo, caminho)
    for metodo, caminho in [("GET", "/conta"), ("POST", "/"), ("GET", "/openapi.json"), ("GET", "/qualquer"),
                            ("GET", "/auth/v1x"), ("POST", "/notion/callback"), ("GET", "/ws/ocr")]:
        assert not eh_publica(metodo, caminho)


# ---------- middleware: login obrigatorio ----------

def test_rota_desconhecida_e_docs_tambem_exigem_login(cliente):
    for caminho in ("/qualquer-coisa", "/openapi.json", "/docs", "/redoc", "/admin", "/.env"):
        resp = cliente.get(caminho)
        assert resp.status_code == 401, caminho
        assert resp.json()["codigo"] == "NAO_AUTENTICADO"
    # logado, a rota inexistente e 404 no mesmo formato de erro, e os docs nao existem
    for caminho in ("/qualquer-coisa", "/openapi.json", "/docs"):
        resp = cliente.get(caminho, headers=auth())
        assert resp.status_code == 404 and resp.json()["codigo"] == "NAO_ENCONTRADO"


@pytest.mark.parametrize("cabecalho", [
    "Basic dXNlcjpzZW5oYQ==", "Bearer", "Bearer   ", "bearer token-falso", "Bearer " + "a" * 5000, "token-ana",
])
def test_formatos_de_credencial_invalidos(cliente, cabecalho):
    assert cliente.get("/conta", headers={"Authorization": cabecalho}).status_code == 401


def test_token_valido_passa_e_resposta_de_erro_tem_formato_unico(cliente):
    assert cliente.get("/conta", headers=auth()).status_code == 200
    erro = cliente.get("/conta").json()
    assert set(erro) == {"codigo", "mensagem", "request_id"}


def test_ip_bloqueado_depois_de_muitos_tokens_ruins(cliente, ctx):
    ctx.protecao.falhas_auth = Limitador(3, 300)
    for _ in range(3):
        assert cliente.get("/conta", headers=auth("token-falso")).status_code == 401
    # agora ate o token bom desse IP espera a janela esfriar; paginas publicas seguem abertas
    bloqueado = cliente.get("/conta", headers=auth())
    assert bloqueado.status_code == 429 and int(bloqueado.headers["Retry-After"]) >= 1
    assert cliente.get("/saude").status_code == 200


# ---------- middleware: limite de taxa ----------

def test_limite_por_usuario_responde_429_com_cabecalhos(cliente, ctx):
    ctx.protecao = Protecao(ctx.config, (Politica("usuario.geral", "usuario", 3, 60),))
    for _ in range(3):
        resp = cliente.get("/conta", headers=auth())
        assert resp.status_code == 200 and "ratelimit-remaining" in resp.headers
    resp = cliente.get("/conta", headers=auth())
    assert resp.status_code == 429 and resp.json()["codigo"] == "LIMITE_EXCEDIDO"
    assert int(resp.headers["Retry-After"]) >= 1 and resp.headers["RateLimit-Remaining"] == "0"
    # o limite e por usuario, nao global
    assert cliente.get("/conta", headers=auth("token-bia")).status_code == 200


def test_limite_por_ip_vale_ate_para_rotas_publicas(cliente, ctx):
    ctx.protecao = Protecao(ctx.config, (Politica("ip.geral", "ip", 2, 60),))
    assert cliente.get("/saude").status_code == 200 and cliente.get("/saude").status_code == 200
    assert cliente.get("/saude").status_code == 429


def test_limite_do_login_e_mais_apertado(cliente, ctx):
    ctx.http = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={})))
    codigos = [cliente.post("/auth/v1/token?grant_type=refresh_token", json={}).status_code for _ in range(25)]
    assert codigos[:20] == [200] * 20 and set(codigos[20:]) == {429}


def test_limite_de_exclusao_de_conta(cliente):
    codigos = [cliente.delete("/conta", headers=auth()).status_code for _ in range(5)]
    assert codigos == [200, 200, 200, 429, 429]


def test_limite_do_websocket(cliente, ctx):
    ctx.protecao = Protecao(ctx.config, (Politica("ip.websocket", "ip", 1, 60, "/ws/ocr"),))
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json({"acao": "ping"})
        assert ws.receive_json()["tipo"] == "pong"
    with pytest.raises(WebSocketDisconnect):
        with cliente.websocket_connect("/ws/ocr", headers=auth()):
            pass


# ---------- middleware: corpo, host, cabecalhos ----------

def test_corpo_grande_demais_e_recusado(cliente, ctx):
    ctx.http = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={})))
    limite = Configuracao().max_corpo_bytes
    resp = cliente.post("/auth/v1/token?grant_type=id_token", content=b"x" * (limite + 1))
    assert resp.status_code == 413 and resp.json()["codigo"] == "CORPO_GRANDE_DEMAIS"
    # em pedacos (sem Content-Length) tambem
    resp = cliente.post("/auth/v1/token?grant_type=id_token", content=iter([b"x" * limite, b"x"]))
    assert resp.status_code == 413
    assert cliente.post("/auth/v1/token?grant_type=id_token", content=b"x" * limite).status_code == 200


def test_host_desconhecido_e_recusado(cliente):
    assert cliente.get("/saude", headers={"Host": "evil.example.com"}).status_code == 400


def test_cabecalhos_de_seguranca_em_toda_resposta(cliente):
    for resp in (cliente.get("/saude"), cliente.get("/privacidade"), cliente.get("/conta"), cliente.get("/conta", headers=auth())):
        h = resp.headers
        assert h["x-content-type-options"] == "nosniff" and h["x-frame-options"] == "DENY"
        assert h["referrer-policy"] == "no-referrer" and "max-age" in h["strict-transport-security"]
        assert "frame-ancestors 'none'" in h["content-security-policy"] and h["x-request-id"]
    assert cliente.get("/conta", headers=auth()).headers["cache-control"] == "no-store"
    assert "script-src" not in cliente.get("/privacidade").headers["content-security-policy"]


def test_callback_do_oauth_tem_csp_com_nonce(cliente):
    resp = cliente.get("/notion/callback", params={"error": "access_denied"})
    csp = resp.headers["content-security-policy"]
    nonce = csp.split("script-src 'nonce-")[1].split("'")[0]
    assert f'<script nonce="{nonce}">' in resp.text and "'unsafe-inline'" not in csp


def test_request_id_do_cliente_so_vale_se_for_seguro(cliente):
    assert cliente.get("/saude", headers={"X-Request-ID": "abc12345-def"}).headers["x-request-id"] == "abc12345-def"
    gerado = cliente.get("/saude", headers={"X-Request-ID": "x\r\ny"}).headers["x-request-id"]
    assert gerado != "x\r\ny" and len(gerado) == 32


def test_erro_inesperado_nao_vaza_detalhe(cliente, ctx):
    async def quebra(*a, **k):
        raise RuntimeError("senha do banco: hunter2")

    ctx.notion.status = quebra
    resp = cliente.get("/conta", headers=auth())
    assert resp.status_code == 500 and "hunter2" not in resp.text
    assert resp.json()["codigo"] == "ERRO_INTERNO" and resp.json()["request_id"]


def test_banco_fora_do_ar_vira_502_sem_detalhe(cliente, ctx):
    async def fora(*a, **k):
        raise httpx.ConnectError("conexao recusada em http://rest:3000 com a chave k")

    ctx.notion.status = fora
    resp = cliente.get("/conta", headers=auth())
    assert resp.status_code == 502 and resp.json()["codigo"] == "SERVICO_EXTERNO_INDISPONIVEL"
    assert "rest:3000" not in resp.text


def test_cors_nao_e_liberado(cliente):
    resp = cliente.options("/conta", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"})
    assert "access-control-allow-origin" not in resp.headers


# ---------- proxy de login ----------

def test_proxy_so_aceita_metodos_e_logins_previstos(cliente, ctx):
    pedidos = []

    def handler(req):
        pedidos.append(req)
        return httpx.Response(200, json={})

    ctx.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    assert cliente.post("/auth/v1/token?grant_type=password", json={}).status_code == 400
    assert cliente.post("/auth/v1/token", json={}).status_code == 400
    assert cliente.get("/auth/v1/token?grant_type=id_token").status_code == 405
    assert cliente.delete("/auth/v1/user").status_code == 405
    assert not pedidos
    assert cliente.post("/auth/v1/token?grant_type=id_token", json={}).status_code == 200
    assert pedidos[0].headers["x-forwarded-for"]


# ---------- websocket ----------

def test_websocket_expira_se_o_pedido_nao_chega(cliente, ctx):
    object.__setattr__(ctx.config, "ws_espera_segundos", 0.05)
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        assert "Tempo esgotado" in ws.receive_json()["mensagem"]


def test_websocket_um_processamento_por_usuario(cliente, ctx):
    ctx.protecao.simultaneos.entrar("uid-ana")  # ja tem um rodando
    from tests.test_websocket import pedido, receber_ate_fim

    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        assert "em andamento" in receber_ate_fim(ws)[-1]["mensagem"]
    # outro usuario nao e afetado pelo limite do primeiro
    assert ctx.protecao.simultaneos.entrar("uid-bia")


def test_websocket_ignora_lixo_sem_derrubar(cliente):
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_bytes(b"\x00\x01")
        assert ws.receive_json()["tipo"] == "erro"
        ws.send_text("[1,2]")
        assert ws.receive_json()["tipo"] == "erro"
        ws.send_json({"acao": "<script>"})
        assert "<script>" not in ws.receive_json()["mensagem"]


def test_websocket_nao_aceita_mensagens_demais_sem_pedido(cliente):
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        for _ in range(20):
            ws.send_json({"acao": "ping"})
            assert ws.receive_json()["tipo"] == "pong"
        ws.send_json({"acao": "ping"})
        with pytest.raises(WebSocketDisconnect):
            while True:
                ws.receive_json()


# ---------- pedido ----------

def test_pedido_recusa_id_de_pagina_estranho_e_imagem_gigante():
    from ocr.pedido import ErroPedido, validar_pedido

    config = Configuracao()
    base = {"titulo": "T", "id_pagina": "p", "modalidades": {"topicos": True}, "imagens": ["YQ=="]}
    for alteracao in ({"id_pagina": "../../etc/passwd"}, {"id_pagina": "a" * 65}, {"id_pagina": "x y"},
                      {"imagens": ["A" * (config.max_bytes_imagem * 2)]}):
        with pytest.raises(ErroPedido):
            validar_pedido({**base, **alteracao}, config)
