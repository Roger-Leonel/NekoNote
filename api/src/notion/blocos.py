"""Transforma a nota gerada pela IA em blocos do Notion."""
import re

from ia.esquema import NotaGerada

ICONE_PADRAO = "🐾"  # se a IA nao devolver um emoji valido
TITULO_PADRAO = "Nova nota"
TAMANHO_MAX_TITULO = 100
# limites do notion: 100 blocos por pedido e 2000 letras por trecho
MAX_BLOCOS = 100
MAX_TEXTO = 2000


def _rich_text(conteudo: str) -> list[dict]:
    return [{"type": "text", "text": {"content": conteudo[i:i + MAX_TEXTO]}} for i in range(0, len(conteudo), MAX_TEXTO)]


def _bloco(tipo: str, conteudo: str, **extra) -> dict:
    return {"object": "block", "type": tipo, tipo: {"rich_text": _rich_text(conteudo), **extra}}


def _paragrafos(texto: str) -> list[str]:
    return [p.strip() for p in re.split(r"\n{2,}", texto.strip()) if p.strip()]


def _item(conteudo: str, filhos: list[dict] | None = None) -> dict:
    return _bloco("bulleted_list_item", conteudo, **({"children": filhos} if filhos else {}))


def montar_blocos(nota: NotaGerada, modalidades: dict[str, bool]) -> list[dict]:
    """Ordem fixa: resumo, assuntos, topicos, apresentacao; so o que o usuario ligou (mesmo que a IA devolva o resto)."""
    blocos: list[dict] = []

    if modalidades.get("resumo"):
        paragrafos = _paragrafos(nota.resumo)
        if paragrafos:
            blocos += [_bloco("heading_2", "Resumo"), *(_bloco("paragraph", p) for p in paragrafos)]

    if modalidades.get("assunto"):
        assuntos = [a for a in nota.assuntos if a.titulo.strip() or a.conteudo.strip()]
        if assuntos:
            blocos.append(_bloco("heading_2", "Assuntos"))
            for a in assuntos:
                if a.titulo.strip():
                    blocos.append(_bloco("heading_3", a.titulo.strip()))
                blocos += [_bloco("paragraph", p) for p in _paragrafos(a.conteudo)]

    if modalidades.get("topicos"):
        topicos = [t for t in nota.topicos if t.titulo.strip()]
        if topicos:
            blocos.append(_bloco("heading_2", "Tópicos"))
            for t in topicos:
                filhos = [_item(s.strip()) for s in t.subtopicos if s.strip()][:MAX_BLOCOS]
                blocos.append(_item(t.titulo.strip(), filhos))

    if modalidades.get("apresentacao"):
        slides = [s for s in nota.apresentacao if s.titulo.strip()]
        if slides:
            blocos.append(_bloco("heading_2", "Apresentação"))
            for i, slide in enumerate(slides, 1):
                blocos.append(_bloco("heading_3", f"Slide {i}: {slide.titulo.strip()}"))
                blocos += [_item(p.strip()) for p in slide.pontos if p.strip()]
                if slide.fala.strip():
                    blocos.append(_bloco("quote", slide.fala.strip()))

    return blocos


def em_lotes(blocos: list[dict], tamanho: int = MAX_BLOCOS) -> list[list[dict]]:
    return [blocos[i:i + tamanho] for i in range(0, len(blocos), tamanho)]


def titulo_da_nota(nota: NotaGerada) -> str:
    titulo = " ".join(nota.titulo.split())
    return titulo[:TAMANHO_MAX_TITULO] or TITULO_PADRAO


def icone_da_nota(nota: NotaGerada) -> str:
    """O emoji escolhido pela IA; qualquer coisa que nao pareca um emoji (texto, vazio, longo demais) vira o padrao."""
    emoji = nota.emoji.strip()
    valido = 0 < len(emoji) <= 12 and not any(c.isascii() and c.isalnum() for c in emoji) and not emoji.isascii()
    return emoji if valido else ICONE_PADRAO


def corpo_da_pagina(id_pagina: str, titulo: str, icone: str, primeiro_lote: list[dict]) -> dict:
    return {
        "parent": {"type": "page_id", "page_id": id_pagina},
        "icon": {"type": "emoji", "emoji": icone},
        "properties": {"title": {"title": [{"type": "text", "text": {"content": titulo}}]}},
        "children": primeiro_lote,
    }
