from dataclasses import dataclass, field
from typing import Any

from erros import ErroApi

from armazenamento import ArmazemUsuarios
from configuracao import Configuracao
from limite import LimitePorUsuario
from ocr.tarefas import Tarefas
from protecao.limites import Protecao
from seguranca import Cifra


@dataclass
class Contexto:
    config: Configuracao
    armazem: ArmazemUsuarios
    cifra: Cifra
    verificador: Any
    limite: LimitePorUsuario
    notion: Any
    ocr: Any
    ia: Any
    remover_usuario: Any
    protecao: Protecao
    http: Any = None
    tarefas: Tarefas = field(default_factory=Tarefas)

_contexto: Contexto | None = None


def definir_contexto(ctx: Contexto | None) -> None:
    global _contexto
    _contexto = ctx


def contexto_atual() -> Contexto | None:
    return _contexto


def obter_contexto() -> Contexto:
    if _contexto is None:
        raise ErroApi(503, "NAO_CONFIGURADO", "Servidor ainda não configurado.")
    return _contexto
