"""O que a IA devolve (resumo, assuntos, topicos, apresentacao) e como validar a resposta."""
from pydantic import BaseModel, Field


class Assunto(BaseModel):
    titulo: str = ""
    conteudo: str = ""


class Topico(BaseModel):
    titulo: str = ""
    subtopicos: list[str] = Field(default_factory=list)


class Slide(BaseModel):
    titulo: str = ""
    pontos: list[str] = Field(default_factory=list)
    fala: str = ""


class NotaGerada(BaseModel):
    # a IA escolhe o titulo e o emoji da pagina
    titulo: str = ""
    emoji: str = ""
    resumo: str = ""
    assuntos: list[Assunto] = Field(default_factory=list)
    topicos: list[Topico] = Field(default_factory=list)
    apresentacao: list[Slide] = Field(default_factory=list)


def _objeto(propriedades: dict) -> dict:
    # o modo estrito da OpenAI exige additionalProperties=false e todos os campos em "required"
    return {"type": "object", "properties": propriedades, "required": list(propriedades), "additionalProperties": False}


_TEXTO = {"type": "string"}
_LISTA_TEXTO = {"type": "array", "items": _TEXTO}

ESQUEMA_JSON = _objeto({
    "titulo": _TEXTO,
    "emoji": _TEXTO,
    "resumo": _TEXTO,
    "assuntos": {"type": "array", "items": _objeto({"titulo": _TEXTO, "conteudo": _TEXTO})},
    "topicos": {"type": "array", "items": _objeto({"titulo": _TEXTO, "subtopicos": _LISTA_TEXTO})},
    "apresentacao": {"type": "array", "items": _objeto({"titulo": _TEXTO, "pontos": _LISTA_TEXTO, "fala": _TEXTO})},
})
