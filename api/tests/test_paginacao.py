import asyncio
import json

import httpx

from configuracao import Configuracao
from notion.cliente import ClienteNotion


def _pagina(n: int) -> dict:
    return {"id": f"pag-{n}", "object": "page", "properties": {"title": {"type": "title", "title": [{"plain_text": f"P{n}"}]}}}


# notion falso que devolve 250 paginas em levas, com cursor
def _notion_com_250_paginas():
    todas = [_pagina(n) for n in range(250)]
    corpos: list[dict] = []

    def handler(req: httpx.Request) -> httpx.Response:
        corpo = json.loads(req.content)
        corpos.append(corpo)
        inicio = int(corpo.get("start_cursor", 0))
        fim = inicio + corpo["page_size"]
        resp = {"results": todas[inicio:fim], "has_more": fim < len(todas), "next_cursor": str(fim) if fim < len(todas) else None}
        return httpx.Response(200, json=resp)

    return handler, corpos


def test_busca_le_todas_as_levas_com_cursor():
    handler, corpos = _notion_com_250_paginas()
    cliente = ClienteNotion(Configuracao(), httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    paginas = asyncio.run(cliente.buscar_paginas("token"))
    assert [p["id"] for p in paginas] == [f"pag-{n}" for n in range(250)]
    assert [c.get("start_cursor") for c in corpos] == [None, "100", "200"]


def test_busca_respeita_o_maximo():
    handler, corpos = _notion_com_250_paginas()
    cliente = ClienteNotion(Configuracao(), httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    paginas = asyncio.run(cliente.buscar_paginas("token", maximo=120))
    assert len(paginas) == 120
    assert [c["page_size"] for c in corpos] == [100, 20]
