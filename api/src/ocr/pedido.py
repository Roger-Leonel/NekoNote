import base64
import binascii
import hashlib
import json
import re
import uuid
from dataclasses import dataclass

from configuracao import Configuracao
from ia.prompt import TEMPERAMENTO_PADRAO, TEMPERAMENTOS as _DESCRICOES_TEMPERAMENTO


class ErroPedido(Exception):
    pass


# tom do texto gerado; o app so manda um destes (qualquer outro valor vira o padrao). Os textos ficam em ia/prompt.py
TEMPERAMENTOS = tuple(_DESCRICOES_TEMPERAMENTO)


@dataclass(frozen=True)
class Modalidades:
    topicos: bool
    resumo: bool
    assunto: bool
    apresentacao: bool = False

    def como_dict(self) -> dict[str, bool]:
        return {"topicos": self.topicos, "resumo": self.resumo, "assunto": self.assunto, "apresentacao": self.apresentacao}


@dataclass(frozen=True)
class Pedido:
    id_pagina: str
    modalidades: Modalidades
    imagens: list[bytes]
    temperamento: str = TEMPERAMENTO_PADRAO
    # escolhido pelo app e igual em todas as tentativas do mesmo envio (ver ocr/tarefas.py)
    id_pedido: str = ""
    # resumo do conteudo: o mesmo id_pedido com conteudo diferente e erro do cliente
    assinatura: str = ""


# ids do Notion: uuid com ou sem hifens
_ID_PAGINA = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_ID_PEDIDO = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def _decodificar(imagem: object, limite_bytes: int) -> bytes:
    if not isinstance(imagem, str) or not imagem:
        raise ErroPedido("Imagem inválida.")
    if imagem.startswith("data:"):
        imagem = imagem.split(",", 1)[-1]
    # base64 ocupa 4/3 do tamanho real: recusa antes de decodificar
    if len(imagem) > limite_bytes * 4 // 3 + 8:
        raise ErroPedido("Uma das imagens é grande demais.")
    try:
        dados = base64.b64decode(imagem, validate=True)
    except (binascii.Error, ValueError):
        raise ErroPedido("Imagem inválida.")
    if not dados:
        raise ErroPedido("Imagem vazia.")
    if len(dados) > limite_bytes:
        raise ErroPedido("Uma das imagens é grande demais.")
    return dados


def validar_pedido(mensagem: dict, config: Configuracao) -> Pedido:
    id_pagina = str(mensagem.get("id_pagina") or "").strip()
    if not id_pagina:
        raise ErroPedido("Escolha a página de inserção.")
    if not _ID_PAGINA.match(id_pagina):
        raise ErroPedido("Página de inserção inválida.")

    bruto = mensagem.get("modalidades")
    if not isinstance(bruto, dict):
        raise ErroPedido("Escolha ao menos uma opção: tópicos, resumo, assunto ou apresentação.")
    modalidades = Modalidades(
        topicos=bool(bruto.get("topicos")), resumo=bool(bruto.get("resumo")), assunto=bool(bruto.get("assunto")),
        apresentacao=bool(bruto.get("apresentacao")),
    )
    if not any(modalidades.como_dict().values()):
        raise ErroPedido("Escolha ao menos uma opção: tópicos, resumo, assunto ou apresentação.")

    temperamento = str(mensagem.get("temperamento") or "").strip().lower()
    if temperamento not in TEMPERAMENTOS:
        temperamento = TEMPERAMENTO_PADRAO

    imagens = mensagem.get("imagens")
    if not isinstance(imagens, list) or not imagens:
        raise ErroPedido("Nenhuma imagem fornecida.")
    if len(imagens) > config.max_imagens:
        raise ErroPedido(f"Envie no máximo {config.max_imagens} imagens por vez.")

    decodificadas = [_decodificar(i, config.max_bytes_imagem) for i in imagens]

    # sem id_pedido (app antigo) cada envio vale por si: gera um id novo e nao ha reaproveitamento
    id_pedido = str(mensagem.get("id_pedido") or "").strip()
    if id_pedido and not _ID_PEDIDO.match(id_pedido):
        raise ErroPedido("Identificador do pedido inválido.")
    id_pedido = id_pedido or uuid.uuid4().hex

    resumo = hashlib.sha256(json.dumps([id_pagina, modalidades.como_dict(), temperamento]).encode())
    for dados in decodificadas:
        resumo.update(hashlib.sha256(dados).digest())
    return Pedido(id_pagina, modalidades, decodificadas, temperamento, id_pedido, resumo.hexdigest())
