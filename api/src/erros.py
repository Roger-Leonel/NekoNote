"""Contrato de erro unico da API.

Toda resposta de erro (4xx/5xx), venha de rota, validacao, middleware ou excecao inesperada, tem o mesmo formato:

    {"codigo": "NAO_AUTENTICADO", "mensagem": "Autenticação necessária.", "request_id": "..."}

`mensagem` e para o usuario (nunca detalhe interno); `codigo` e estavel e serve para o app decidir o que fazer.
Campos extras (ex.: `status` nos erros do Notion) so entram quando a rota pede, para nao quebrar o app existente.
"""
import logging

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("nekonote.erros")

CODIGOS_POR_STATUS = {
    400: "REQUISICAO_INVALIDA",
    401: "NAO_AUTENTICADO",
    403: "PROIBIDO",
    404: "NAO_ENCONTRADO",
    405: "METODO_NAO_PERMITIDO",
    409: "CONFLITO",
    413: "CORPO_GRANDE_DEMAIS",
    422: "VALIDACAO",
    429: "LIMITE_EXCEDIDO",
    500: "ERRO_INTERNO",
    502: "SERVICO_EXTERNO_INDISPONIVEL",
    503: "INDISPONIVEL",
}

MENSAGENS_PADRAO = {
    401: "Autenticação necessária.",
    404: "Recurso não encontrado.",
    405: "Método não permitido.",
    500: "Erro interno. Tente novamente.",
}


class ErroApi(Exception):
    def __init__(self, status: int, codigo: str | None = None, mensagem: str | None = None,
                 cabecalhos: dict[str, str] | None = None, extras: dict | None = None):
        self.status = status
        self.codigo = codigo or CODIGOS_POR_STATUS.get(status, "ERRO")
        self.mensagem = mensagem or MENSAGENS_PADRAO.get(status, "Não foi possível concluir a operação.")
        self.cabecalhos = cabecalhos or {}
        self.extras = extras or {}
        super().__init__(self.mensagem)


def corpo_erro(codigo: str, mensagem: str, request_id: str | None = None, **extras) -> dict:
    corpo = {"codigo": codigo, "mensagem": mensagem, **extras}
    if request_id:
        corpo["request_id"] = request_id
    return corpo


def _request_id(request: Request) -> str | None:
    return getattr(request.state, "request_id", None)


def resposta_erro(request: Request, erro: ErroApi) -> JSONResponse:
    return JSONResponse(
        corpo_erro(erro.codigo, erro.mensagem, _request_id(request), **erro.extras),
        status_code=erro.status,
        headers=erro.cabecalhos,
    )


def instalar_tratadores(app: FastAPI) -> None:
    @app.exception_handler(ErroApi)
    async def _erro_api(request: Request, erro: ErroApi):
        return resposta_erro(request, erro)

    @app.exception_handler(StarletteHTTPException)
    async def _erro_http(request: Request, erro: StarletteHTTPException):
        # o texto padrao do starlette/fastapi ("Not Found", ...) nao vai pro usuario; so mensagens que nos escrevemos
        mensagem = erro.detail if isinstance(erro.detail, str) and erro.status_code not in (404, 405) else None
        return resposta_erro(
            request, ErroApi(erro.status_code, mensagem=mensagem, cabecalhos=dict(erro.headers or {}))
        )

    @app.exception_handler(RequestValidationError)
    async def _erro_validacao(request: Request, erro: RequestValidationError):
        # so onde e o que falhou; nunca devolve o valor enviado
        campos = [{"campo": ".".join(str(p) for p in e["loc"]), "problema": e["type"]} for e in erro.errors()]
        return resposta_erro(request, ErroApi(422, mensagem="Dados inválidos.", extras={"campos": campos}))

    @app.exception_handler(httpx.HTTPError)
    async def _erro_dependencia(request: Request, erro: httpx.HTTPError):
        # Supabase/Notion/OpenAI fora do ar ou lentos: o usuario ve 502, o detalhe (URL, chave) fica so no log
        logger.warning("dependencia indisponivel (request_id=%s): %s", _request_id(request), type(erro).__name__)
        return resposta_erro(request, ErroApi(502, "SERVICO_EXTERNO_INDISPONIVEL", "Um serviço de apoio está indisponível. Tente novamente."))

    @app.exception_handler(Exception)
    async def _erro_inesperado(request: Request, erro: Exception):
        logger.error("erro inesperado (request_id=%s)", _request_id(request), exc_info=erro)
        return resposta_erro(request, ErroApi(500))
