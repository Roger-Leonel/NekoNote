"""Formato de saida de cada rota (o contrato com o app). Campo novo entra como opcional; nenhum e removido ou muda de tipo."""
from typing import Literal

from pydantic import BaseModel


class StatusNotion(BaseModel):
    status: Literal["conectado", "reconectar", "desconectado"]
    espaco_nome: str | None = None
    espaco_icone: str | None = None
    paginas: int | None = None


class ContaResposta(BaseModel):
    email: str
    id_publico: str
    notion: StatusNotion
    cadastro_concluido: bool


class PaginaNotion(BaseModel):
    id: str
    titulo: str
    icone: str | None = None
    pai: str | None = None


class PaginasResposta(BaseModel):
    paginas: list[PaginaNotion]


class Nota(BaseModel):
    id: str
    titulo: str
    subtitulo: str
    topicos: list[str]
    data: str
    url: str


class NotasResposta(BaseModel):
    notas: list[Nota]


class UrlAutorizacaoResposta(BaseModel):
    url_autorizacao: str


class OperacaoResposta(BaseModel):
    status: Literal["ok", "desconectado", "excluida"]
