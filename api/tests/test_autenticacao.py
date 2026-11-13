import base64
import hashlib
import hmac
import json

import httpx
import pytest

from autenticacao import TokenInvalido, criar_verificador_supabase, verificar_jwt

SEGREDO = "segredo-do-jwt"
AGORA = 1_800_000_000


def _b64(dados: bytes) -> str:
    return base64.urlsafe_b64encode(dados).rstrip(b"=").decode()


def jwt(corpo: dict, segredo: str = SEGREDO, alg: str = "HS256") -> str:
    cab = _b64(json.dumps({"alg": alg, "typ": "JWT"}).encode())
    cor = _b64(json.dumps(corpo).encode())
    assinatura = hmac.new(segredo.encode(), f"{cab}.{cor}".encode(), hashlib.sha256).digest()
    return f"{cab}.{cor}.{_b64(assinatura)}"


def corpo_valido(**extra) -> dict:
    return {"sub": "uuid-ana", "email": "ana@exemplo.com", "aud": "authenticated", "role": "authenticated", "exp": AGORA + 3600, **extra}


def test_token_valido_devolve_as_informacoes_do_usuario():
    dados = verificar_jwt(jwt(corpo_valido()), SEGREDO, agora=AGORA)
    assert dados["sub"] == "uuid-ana" and dados["email"] == "ana@exemplo.com"


@pytest.mark.parametrize("token,motivo", [
    (jwt(corpo_valido(), segredo="outro-segredo"), "assinatura"),
    (jwt(corpo_valido(exp=AGORA - 1000)), "expirado"),
    (jwt(corpo_valido(role="anon")), "usuario"),
    (jwt(corpo_valido(role="service_role")), "usuario"),
    (jwt(corpo_valido(aud="outra")), "usuario"),
    (jwt({k: v for k, v in corpo_valido().items() if k != "sub"}), "usuario"),
    (jwt({k: v for k, v in corpo_valido().items() if k != "exp"}), "expirado"),
    (jwt(corpo_valido(), alg="none"), "algoritmo"),
    ("lixo", "formato"),
    ("a.b.c", "ilegivel"),
])
def test_token_invalido_e_recusado(token, motivo):
    with pytest.raises(TokenInvalido):
        verificar_jwt(token, SEGREDO, agora=AGORA)


def test_token_expirado_ha_pouco_tem_tolerancia_de_relogio():
    assert verificar_jwt(jwt(corpo_valido(exp=AGORA - 30)), SEGREDO, agora=AGORA)["sub"] == "uuid-ana"


def test_verificador_assincrono(monkeypatch):
    import asyncio
    import time

    monkeypatch.setattr(time, "time", lambda: AGORA)
    verificar = criar_verificador_supabase(SEGREDO)
    usuario = asyncio.run(verificar(jwt(corpo_valido())))
    assert (usuario.id, usuario.email) == ("uuid-ana", "ana@exemplo.com")
    with pytest.raises(TokenInvalido):
        asyncio.run(verificar(jwt(corpo_valido(), segredo="x")))


def test_proxy_repassa_so_as_rotas_do_app(cliente, ctx):
    pedidos = []

    def handler(req: httpx.Request) -> httpx.Response:
        pedidos.append(req)
        return httpx.Response(200, json={"access_token": "t"})

    ctx.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    resp = cliente.post("/auth/v1/token?grant_type=id_token", json={"provider": "google", "id_token": "x"})
    assert resp.status_code == 200 and resp.json() == {"access_token": "t"}
    assert str(pedidos[0].url) == "http://auth:9999/token?grant_type=id_token"
    assert json.loads(pedidos[0].content) == {"provider": "google", "id_token": "x"}

    # administracao e qualquer outra rota nao saem pela internet
    for rota in ("/auth/v1/admin/users", "/auth/v1/signup", "/auth/v1/invite", "/auth/v1/otp"):
        assert cliente.get(rota).status_code == 404 and cliente.post(rota, json={}).status_code == 404
    assert len(pedidos) == 1


def test_proxy_avisa_quando_o_login_esta_fora_do_ar(cliente, ctx):
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sem conexao")

    ctx.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    assert cliente.post("/auth/v1/token?grant_type=id_token", json={}).status_code == 502
