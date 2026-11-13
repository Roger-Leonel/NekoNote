"""Gera os segredos do Supabase auto-hospedado e imprime as linhas para colar no api/.env.

    python supabase/gerar_chaves.py
"""
import base64
import hashlib
import hmac
import json
import secrets
import time


def _b64(dados: bytes) -> str:
    return base64.urlsafe_b64encode(dados).rstrip(b"=").decode()


def jwt(role: str, segredo: str, anos: int = 10) -> str:
    cabecalho = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    agora = int(time.time())
    corpo = _b64(json.dumps({"role": role, "iss": "supabase", "iat": agora, "exp": agora + anos * 365 * 86400}, separators=(",", ":")).encode())
    assinatura = _b64(hmac.new(segredo.encode(), f"{cabecalho}.{corpo}".encode(), hashlib.sha256).digest())
    return f"{cabecalho}.{corpo}.{assinatura}"


if __name__ == "__main__":
    jwt_secret = secrets.token_hex(32)
    print(f"POSTGRES_PASSWORD={secrets.token_hex(24)}")
    print(f"JWT_SECRET={jwt_secret}")
    print(f"SUPABASE_SERVICE_KEY={jwt('service_role', jwt_secret)}")
    print(f"SUPABASE_ANON_KEY={jwt('anon', jwt_secret)}")
