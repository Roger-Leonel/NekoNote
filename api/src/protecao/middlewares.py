"""Cadeia de middlewares da API.

De fora para dentro (a ordem importa):

    Requisicao            id de rastreio, log de acesso, rede de seguranca para erros inesperados
    CabecalhosDeSeguranca cabecalhos em toda resposta, inclusive as de erro das camadas de baixo
    HostConfiavel         recusa Host que nao seja o nosso (evita host-header attack)
    LimiteDeCorpo         413 para corpo HTTP grande demais
    Autenticacao          limite por IP, login obrigatorio (negado por padrao), limite por usuario
    rotas                 so veem requisicoes ja autenticadas

CORS fica desligado de proposito: o unico cliente e o app Android, que nao usa CORS; assim nenhum site consegue
chamar a API a partir do navegador de um usuario.

Tudo aqui e ASGI puro, para valer igual em HTTP e WebSocket.
"""
import json
import logging
import re
import time
import uuid

from fastapi import FastAPI
from starlette.middleware.trustedhost import TrustedHostMiddleware

from autenticacao import TokenInvalido, extrair_bearer
from configuracao import Configuracao
from contexto import contexto_atual
from erros import corpo_erro
from protecao.limites import Decisao, ip_do_cliente
from protecao.rotas_publicas import eh_publica


# ---------- pecas comuns ----------

CABECALHOS_PADRAO: tuple[tuple[bytes, bytes], ...] = (
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"no-referrer"),
    (b"cache-control", b"no-store"),
    (b"cross-origin-resource-policy", b"same-origin"),
    (b"permissions-policy", b"camera=(), microphone=(), geolocation=(), payment=()"),
    (b"strict-transport-security", b"max-age=63072000; includeSubDomains"),
)
CSP_API = b"default-src 'none'; frame-ancestors 'none'"
CSP_PAGINA = (b"default-src 'self'; img-src 'self' data:; style-src 'self'; base-uri 'none'; "
              b"form-action 'none'; frame-ancestors 'none'")


def request_id_de(scope: dict) -> str | None:
    return scope.get("state", {}).get("request_id")


def ip_de(scope: dict) -> str:
    return scope.get("state", {}).get("ip", "-")


async def enviar_erro(scope: dict, send, status: int, codigo: str, mensagem: str,
                      cabecalhos: dict[str, str] | None = None, **extras) -> None:
    """Responde um erro no formato unico da API, antes de a requisicao chegar numa rota."""
    if scope["type"] == "websocket":
        # recusa o handshake com o status HTTP quando o servidor suporta; senao fecha com "violacao de politica"
        if "websocket.http.response" in scope.get("extensions", {}):
            corpo = json.dumps(corpo_erro(codigo, mensagem, request_id_de(scope), **extras)).encode()
            await send({"type": "websocket.http.response.start", "status": status,
                        "headers": _cabecalhos_resposta(corpo, cabecalhos)})
            await send({"type": "websocket.http.response.body", "body": corpo})
        else:
            await send({"type": "websocket.close", "code": 1008})
        return
    corpo = json.dumps(corpo_erro(codigo, mensagem, request_id_de(scope), **extras)).encode()
    await send({"type": "http.response.start", "status": status, "headers": _cabecalhos_resposta(corpo, cabecalhos)})
    await send({"type": "http.response.body", "body": corpo})


def _cabecalhos_resposta(corpo: bytes, extras: dict[str, str] | None) -> list[tuple[bytes, bytes]]:
    cabecalhos = [(b"content-type", b"application/json"), (b"content-length", str(len(corpo)).encode()),
                  (b"content-security-policy", CSP_API), *CABECALHOS_PADRAO]
    cabecalhos += [(k.lower().encode(), v.encode()) for k, v in (extras or {}).items()]
    return cabecalhos


# ---------- Requisicao: id de rastreio, log e rede de seguranca ----------

registro_acesso = logging.getLogger("nekonote.acesso")

_ID_VALIDO = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


class Requisicao:
    """Camada mais externa: id de rastreio, log de acesso e rede de seguranca para erros inesperados.

    O log guarda so metodo, caminho, status, tempo, IP e usuario. Query string fica de fora de proposito: o
    callback do OAuth leva `code` e `state` nela, e cabecalhos (token de login) nunca sao registrados.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            return await self.app(scope, receive, send)

        estado = scope.setdefault("state", {})
        recebido = next((v.decode("latin-1") for n, v in scope["headers"] if n == b"x-request-id"), "")
        request_id = recebido if _ID_VALIDO.match(recebido) else uuid.uuid4().hex
        estado["request_id"] = request_id

        inicio = time.perf_counter()
        status = 0
        iniciou = False

        async def enviar(mensagem):
            nonlocal status, iniciou
            if mensagem["type"] in ("http.response.start", "websocket.http.response.start"):
                status, iniciou = mensagem["status"], True
                mensagem = {**mensagem, "headers": [*mensagem.get("headers", []), (b"x-request-id", request_id.encode())]}
            elif mensagem["type"] == "websocket.close" and not iniciou:
                status, iniciou = 403, True
            elif mensagem["type"] == "websocket.accept":
                status, iniciou = 101, True
                mensagem = {**mensagem, "headers": [*mensagem.get("headers", []), (b"x-request-id", request_id.encode())]}
            await send(mensagem)

        try:
            await self.app(scope, receive, enviar)
        except Exception:
            registro_acesso.exception("falha inesperada (request_id=%s)", request_id)
            if not iniciou:
                status = 500
                await enviar_erro(scope, enviar, 500, "ERRO_INTERNO", "Erro interno. Tente novamente.")
        finally:
            usuario = estado.get("usuario")
            registro_acesso.info(
                "%s %s %s %dms ip=%s usuario=%s id=%s",
                "WS" if scope["type"] == "websocket" else scope["method"], scope["path"], status,
                (time.perf_counter() - inicio) * 1000, ip_de(scope), usuario.id if usuario else "-", request_id,
            )


# ---------- CabecalhosDeSeguranca ----------

class CabecalhosDeSeguranca:
    """Acrescenta os cabecalhos de seguranca em toda resposta HTTP (sem sobrescrever o que a rota ja definiu)."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        async def enviar(mensagem):
            if mensagem["type"] == "http.response.start":
                cabecalhos = list(mensagem.get("headers", []))
                presentes = {nome.lower() for nome, _ in cabecalhos}
                for nome, valor in CABECALHOS_PADRAO:
                    if nome not in presentes:
                        cabecalhos.append((nome, valor))
                if b"content-security-policy" not in presentes:
                    html = any(n.lower() == b"content-type" and v.lower().startswith(b"text/html") for n, v in cabecalhos)
                    cabecalhos.append((b"content-security-policy", CSP_PAGINA if html else CSP_API))
                mensagem = {**mensagem, "headers": cabecalhos}
            await send(mensagem)

        await self.app(scope, receive, enviar)


# ---------- LimiteDeCorpo ----------

class _CorpoGrande(Exception):
    pass


class LimiteDeCorpo:
    """Recusa corpo HTTP acima do limite (413), pelo Content-Length e tambem contando os bytes recebidos.

    As fotos nao passam por aqui: vao pelo WebSocket, cujo tamanho maximo e do servidor (`ws_max_size`).
    """

    def __init__(self, app, maximo: int):
        self.app = app
        self.maximo = maximo

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        declarado = next((v for n, v in scope["headers"] if n == b"content-length"), None)
        if declarado is not None:
            if not declarado.isdigit():
                return await enviar_erro(scope, send, 400, "REQUISICAO_INVALIDA", "Cabeçalho Content-Length inválido.")
            if int(declarado) > self.maximo:
                return await self._recusar(scope, send)

        recebidos = 0
        excedeu = False
        substituiu = False

        async def receber():
            nonlocal recebidos, excedeu
            mensagem = await receive()
            if mensagem["type"] == "http.request":
                recebidos += len(mensagem.get("body", b""))
                if recebidos > self.maximo:
                    excedeu = True
                    raise _CorpoGrande()
            return mensagem

        async def enviar(mensagem):
            nonlocal substituiu
            if excedeu:
                # a rota pode ter engolido a excecao (virado um 400, por exemplo): a resposta certa e 413
                if not substituiu:
                    substituiu = True
                    await self._recusar(scope, send)
                return
            await send(mensagem)

        try:
            await self.app(scope, receber, enviar)
        except _CorpoGrande:
            if not substituiu:
                await self._recusar(scope, send)

    async def _recusar(self, scope, send):
        await enviar_erro(scope, send, 413, "CORPO_GRANDE_DEMAIS", "Requisição grande demais.")


# ---------- Autenticacao: a porta unica da API ----------

registro_auth = logging.getLogger("nekonote.autenticacao")

# um JWT do Supabase tem ~1 KB; acima disso nem tenta decodificar
TAMANHO_MAXIMO_TOKEN = 4096


class Autenticacao:
    """Porta unica da API: nenhuma requisicao (HTTP ou WebSocket) chega a uma rota sem passar por aqui.

    Ordem: limite por IP -> (rota publica? segue) -> IP bloqueado por tokens ruins? -> confere o token ->
    limite por usuario -> rota. Rota fora de `protecao.rotas_publicas` exige token valido, inclusive as que
    ainda nem existem. Depois daqui, `request.state.usuario` e confiavel.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            return await self.app(scope, receive, send)

        estado = scope.setdefault("state", {})
        metodo = "GET" if scope["type"] == "websocket" else scope["method"]
        caminho = scope["path"]
        publica = eh_publica(metodo, caminho)

        ctx = contexto_atual()
        if ctx is None:
            # servidor sem configuracao completa: so as paginas publicas respondem
            if publica:
                return await self.app(scope, receive, send)
            return await enviar_erro(scope, send, 503, "NAO_CONFIGURADO", "Servidor ainda não configurado.")

        protecao = ctx.protecao
        ip = estado["ip"] = ip_do_cliente(scope, protecao.redes_confiaveis)

        negada, folga = protecao.verificar("ip", metodo, caminho, ip)
        if negada:
            return await self._limite_excedido(scope, send, negada)
        if publica:
            return await self.app(scope, receive, self._com_limite(scope, send, folga))

        bloqueio = protecao.falhas_auth.consultar(ip)
        if not bloqueio.permitido:
            registro_auth.warning("ip bloqueado por excesso de tokens invalidos: %s", ip)
            return await self._limite_excedido(scope, send, bloqueio)

        token = next((extrair_bearer(v.decode("latin-1")) for n, v in scope["headers"] if n == b"authorization"), None)
        if token is None or len(token) > TAMANHO_MAXIMO_TOKEN:
            protecao.falhas_auth.tentar(ip)
            return await enviar_erro(scope, send, 401, "NAO_AUTENTICADO", "Autenticação necessária.",
                                     {"WWW-Authenticate": "Bearer"})
        try:
            usuario = await ctx.verificador(token)
        except TokenInvalido:
            protecao.falhas_auth.tentar(ip)
            return await enviar_erro(scope, send, 401, "SESSAO_INVALIDA", "Sessão inválida ou expirada.",
                                     {"WWW-Authenticate": 'Bearer error="invalid_token"'})
        estado["usuario"] = usuario

        negada, folga_usuario = protecao.verificar("usuario", metodo, caminho, usuario.id)
        if negada:
            return await self._limite_excedido(scope, send, negada)
        mais_apertada = min((d for d in (folga, folga_usuario) if d), key=lambda d: d.restante, default=None)
        await self.app(scope, receive, self._com_limite(scope, send, mais_apertada))

    @staticmethod
    async def _limite_excedido(scope, send, decisao: Decisao):
        await enviar_erro(
            scope, send, 429, "LIMITE_EXCEDIDO", "Muitas requisições. Tente de novo em instantes.",
            {"Retry-After": str(decisao.esperar), "RateLimit-Limit": str(decisao.limite), "RateLimit-Remaining": "0"},
            tentar_em_segundos=decisao.esperar,
        )

    @staticmethod
    def _com_limite(scope, send, decisao: Decisao | None):
        if decisao is None or scope["type"] != "http":
            return send

        async def enviar(mensagem):
            if mensagem["type"] == "http.response.start":
                mensagem = {**mensagem, "headers": [
                    *mensagem.get("headers", []),
                    (b"ratelimit-limit", str(decisao.limite).encode()),
                    (b"ratelimit-remaining", str(decisao.restante).encode()),
                ]}
            await send(mensagem)

        return enviar


# ---------- montagem da cadeia ----------

def instalar(app: FastAPI, config: Configuracao) -> None:
    # add_middleware empilha por cima: o ultimo adicionado e o mais externo
    app.add_middleware(Autenticacao)
    app.add_middleware(LimiteDeCorpo, maximo=config.max_corpo_bytes)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=list(config.hosts_permitidos))
    app.add_middleware(CabecalhosDeSeguranca)
    app.add_middleware(Requisicao)
