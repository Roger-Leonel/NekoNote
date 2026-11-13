"""Repassa o login do app para o Supabase Auth (GoTrue), que roda so dentro da rede do Docker.

O app fala com https://<API_DOMAIN>/auth/v1/..., e a API entrega ao GoTrue. E a unica rota alem das paginas
que responde sem token (ainda nao existe token no login), entao e a mais fechada: lista de rotas, metodo e tipo de
login permitidos; o corpo tem teto de tamanho (middleware) e o limite por IP e o mais apertado da API.
"""
import httpx
from fastapi import APIRouter, Depends, Request, Response

from contexto import Contexto, obter_contexto
from erros import ErroApi

router = APIRouter()

# rota -> metodos. O que o app usa: trocar o token do Google e renovar (token), perfil (user), sair (logout)
ROTAS_PERMITIDAS: dict[str, frozenset[str]] = {
    "token": frozenset({"POST"}),
    "user": frozenset({"GET", "PUT"}),
    "logout": frozenset({"POST"}),
    "settings": frozenset({"GET"}),
    "health": frozenset({"GET"}),
}
# em /token so o login com Google (ID token) e a renovacao; "password", "pkce" etc. ficam de fora
TIPOS_DE_LOGIN = frozenset({"id_token", "refresh_token"})
CABECALHOS_QUE_PASSAM = ("authorization", "content-type", "apikey", "accept")


@router.api_route("/auth/v1/{caminho:path}", methods=["GET", "POST", "PUT", "DELETE"], include_in_schema=False)
async def repassar(caminho: str, request: Request, ctx: Contexto = Depends(obter_contexto)) -> Response:
    rota = caminho.strip("/")
    metodos = ROTAS_PERMITIDAS.get(rota)
    if metodos is None:
        raise ErroApi(404)
    if request.method not in metodos:
        raise ErroApi(405)
    if rota == "token" and request.query_params.get("grant_type") not in TIPOS_DE_LOGIN:
        raise ErroApi(400, "LOGIN_NAO_SUPORTADO", "Tipo de login não suportado.")

    cabecalhos = {k: v for k, v in request.headers.items() if k.lower() in CABECALHOS_QUE_PASSAM}
    # o GoTrue aplica limites por IP: sem isto ele enxergaria todo mundo como o IP do tunel
    cabecalhos["X-Forwarded-For"] = request.state.ip
    try:
        resp = await ctx.http.request(
            request.method,
            f"{ctx.config.auth_url.rstrip('/')}/{rota}",
            params=request.query_params,
            content=await request.body(),
            headers=cabecalhos,
        )
    except httpx.HTTPError:
        raise ErroApi(502, "LOGIN_INDISPONIVEL", "Serviço de login indisponível.")
    return Response(content=resp.content, status_code=resp.status_code, media_type=resp.headers.get("content-type"))
