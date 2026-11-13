import base64
import hashlib
import hmac
import time

from cryptography.fernet import Fernet, InvalidToken


# guarda os tokens do notion cifrados
class Cifra:

    def __init__(self, chave: str):
        if not chave:
            raise ValueError("ENCRYPTION_KEY não definida.")
        self._fernet = Fernet(chave.encode())

    def cifrar(self, texto: str) -> str:
        return self._fernet.encrypt(texto.encode()).decode()

    def decifrar(self, texto: str) -> str:
        try:
            return self._fernet.decrypt(texto.encode()).decode()
        except InvalidToken as erro:
            raise ValueError("Não foi possível decifrar o token armazenado.") from erro


def gerar_chave_criptografia() -> str:
    return Fernet.generate_key().decode()


def _assinar(segredo: str, dados: str) -> str:
    mac = hmac.new(segredo.encode(), dados.encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(mac).decode().rstrip("=")


# o state tem o uid, a validade e a assinatura
def criar_state(segredo: str, id_usuario: str, validade_segundos: int, agora: float | None = None) -> str:
    expira = int((agora if agora is not None else time.time()) + validade_segundos)
    uid_b64 = base64.urlsafe_b64encode(id_usuario.encode()).decode().rstrip("=")
    corpo = f"{uid_b64}.{expira}"
    return f"{corpo}.{_assinar(segredo, corpo)}"


def validar_state(segredo: str, state: str, agora: float | None = None) -> str | None:
    try:
        uid_b64, expira, assinatura = state.split(".")
        corpo = f"{uid_b64}.{expira}"
        if not hmac.compare_digest(assinatura, _assinar(segredo, corpo)):
            return None
        if int(expira) < (agora if agora is not None else time.time()):
            return None
        return base64.urlsafe_b64decode(uid_b64 + "=" * (-len(uid_b64) % 4)).decode()
    except (ValueError, UnicodeDecodeError):
        return None
