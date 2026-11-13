import asyncio
import base64
import hashlib
import hmac
import json
import logging
import time
from dataclasses import dataclass
from typing import Awaitable, Callable

from starlette.requests import HTTPConnection

from erros import ErroApi

logger = logging.getLogger("nekonote.autenticacao")


@dataclass(frozen=True)
class Usuario:
    id: str
    email: str

Verificador = Callable[[str], Awaitable[Usuario]]


class TokenInvalido(Exception):
    pass


def _b64_decodificar(parte: str) -> bytes:
    return base64.urlsafe_b64decode(parte + "=" * (-len(parte) % 4))


# Confere o token de acesso que o Supabase Auth (GoTrue) entrega ao app: JWT HS256 assinado com o JWT_SECRET.
def verificar_jwt(token: str, segredo: str, agora: float | None = None, tolerancia: int = 60) -> dict:
    partes = token.split(".")
    if len(partes) != 3:
        raise TokenInvalido("formato inválido")
    cabecalho_b64, corpo_b64, assinatura_b64 = partes
    try:
        cabecalho = json.loads(_b64_decodificar(cabecalho_b64))
        corpo = json.loads(_b64_decodificar(corpo_b64))
        assinatura = _b64_decodificar(assinatura_b64)
    except (ValueError, TypeError) as erro:
        raise TokenInvalido("token ilegível") from erro
    # so HS256: recusa "none" e qualquer outro algoritmo
    if not isinstance(cabecalho, dict) or cabecalho.get("alg") != "HS256" or not isinstance(corpo, dict):
        raise TokenInvalido("algoritmo não aceito")
    esperada = hmac.new(segredo.encode(), f"{cabecalho_b64}.{corpo_b64}".encode(), hashlib.sha256).digest()
    if not hmac.compare_digest(esperada, assinatura):
        raise TokenInvalido("assinatura inválida")
    exp = corpo.get("exp")
    if not isinstance(exp, (int, float)) or (agora if agora is not None else time.time()) > exp + tolerancia:
        raise TokenInvalido("token expirado")
    # so token de usuario logado: as chaves anon e service_role nao servem
    if corpo.get("aud") != "authenticated" or corpo.get("role") != "authenticated" or not corpo.get("sub"):
        raise TokenInvalido("token não é de um usuário")
    return corpo


def criar_verificador_supabase(segredo: str) -> Verificador:
    async def verificar(token: str) -> Usuario:
        try:
            dados = await asyncio.to_thread(verificar_jwt, token, segredo)
        except TokenInvalido as erro:
            # so o motivo, nunca o token
            logger.warning("token do supabase rejeitado: %s", erro)
            raise
        return Usuario(id=dados["sub"], email=dados.get("email", ""))

    return verificar


def extrair_bearer(cabecalho: str | None) -> str | None:
    if not cabecalho:
        return None
    partes = cabecalho.split(" ", 1)
    if len(partes) == 2 and partes[0].lower() == "bearer" and partes[1].strip():
        return partes[1].strip()
    return None


# Quem autentica e o middleware (middleware/autenticacao.py), uma vez por requisicao. Aqui so se le o resultado;
# se uma rota for montada sem o middleware, ela falha fechada em vez de aceitar qualquer um.
def usuario_atual(conexao: HTTPConnection) -> Usuario:
    usuario = getattr(conexao.state, "usuario", None)
    if usuario is None:
        raise ErroApi(401, "NAO_AUTENTICADO", "Autenticação necessária.")
    return usuario
