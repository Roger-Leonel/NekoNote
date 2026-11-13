_RESUMO = {"resumo"}
_ASSUNTOS = {"assunto", "assuntos"}
_TOPICOS = {"topicos", "tópicos", "tópico"}
_APRESENTACAO = {"apresentacao", "apresentação"}


def _texto(rich_text: list[dict]) -> str:
    return "".join(parte.get("plain_text", "") for parte in rich_text or []).strip()


def titulo_da_pagina(pagina: dict) -> str:
    for prop in (pagina.get("properties") or {}).values():
        if prop.get("type") == "title":
            return _texto(prop.get("title", [])) or "Sem título"
    return "Sem título"


def icone_emoji(pagina: dict) -> str | None:
    icone = pagina.get("icon") or {}
    return icone.get("emoji") if icone.get("type") == "emoji" else None


def eh_nota_neko(pagina: dict, id_bot: str | None) -> bool:
    """Nota do NekoNote = pagina criada pela integracao (o emoji agora e escolhido pela IA, entao nao serve de marca)."""
    return bool(id_bot) and (pagina.get("created_by") or {}).get("id") == id_bot


def pai_direto(pagina: dict) -> str | None:
    """Id da pagina em que a pagina esta (None quando e de topo, esta dentro de um bloco ou de um banco)."""
    pai = pagina.get("parent") or {}
    return pai.get("page_id") if pai.get("type") == "page_id" else None


def em_banco(pagina: dict) -> bool:
    """True para as linhas de bancos de dados (tabelas, calendarios, quadros...): nao sao paginas de verdade."""
    return (pagina.get("parent") or {}).get("type") == "database_id"


def pai_bloco(objeto: dict) -> str | None:
    """Id do bloco em que o objeto esta (precisa ser resolvido para a pagina dona do bloco)."""
    pai = objeto.get("parent") or {}
    return pai.get("block_id") if pai.get("type") == "block_id" else None


def pagina_para_app(pagina: dict, pai_do_bloco: str | None = None) -> dict:
    return {
        "id": pagina["id"], "titulo": titulo_da_pagina(pagina), "icone": icone_emoji(pagina),
        "pai": pai_direto(pagina) or pai_do_bloco,
    }


def _texto_do_bloco(bloco: dict) -> str:
    dados = bloco.get(bloco.get("type", ""), {})
    return _texto(dados.get("rich_text", []))


def _secao(titulo: str) -> str | None:
    chave = titulo.strip().lower()
    if chave in _RESUMO:
        return "resumo"
    if chave in _ASSUNTOS:
        return "assuntos"
    if chave in _TOPICOS:
        return "topicos"
    if chave in _APRESENTACAO:
        return "apresentacao"
    return None


def nota_para_app(pagina: dict, blocos: list[dict]) -> dict:
    secao_atual: str | None = None
    # a apresentacao e reconhecida so pra nao misturar os slides com os topicos; os cards nao usam esse texto
    paragrafos: dict[str, list[str]] = {"resumo": [], "assuntos": [], "topicos": [], "apresentacao": []}
    for bloco in blocos:
        tipo = bloco.get("type")
        texto = _texto_do_bloco(bloco)
        if tipo in ("heading_1", "heading_2", "heading_3"):
            secao = _secao(texto)
            if secao is not None:
                secao_atual = secao
            elif secao_atual == "assuntos" and texto:
                paragrafos["assuntos"].append(texto)
            continue
        if secao_atual and texto and tipo in ("paragraph", "bulleted_list_item", "numbered_list_item", "to_do"):
            paragrafos[secao_atual].append(texto)

    subtitulo = (paragrafos["resumo"] or paragrafos["assuntos"] or paragrafos["topicos"] or [""])[0]
    if len(subtitulo) > 90:
        subtitulo = subtitulo[:87].rstrip() + "..."
    return {
        "id": pagina["id"],
        "titulo": titulo_da_pagina(pagina),
        "subtitulo": subtitulo,
        "topicos": [t[:60] for t in paragrafos["topicos"][:3]],
        "data": pagina.get("created_time", ""),
        "url": pagina.get("url", ""),
    }
