import asyncio
import json

import httpx

from armazenamento import ArmazemSupabase


# banco falso no lugar do postgrest: guarda as linhas em memoria
def _postgrest_falso():
    linhas: dict[str, dict] = {}
    pedidos: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        pedidos.append(req)
        id_ = req.url.params.get("id", "").removeprefix("eq.")
        if req.method == "GET":
            achadas = [{**linhas[id_], "atualizado_em": "2026-01-01T00:00:00Z"}] if id_ in linhas else []
            return httpx.Response(200, json=achadas)
        if req.method == "POST":
            corpo = json.loads(req.content)
            linhas[corpo["id"]] = corpo
            return httpx.Response(201)
        if req.method == "DELETE":
            linhas.pop(id_, None)
            return httpx.Response(204)
        return httpx.Response(405)

    return handler, linhas, pedidos


def test_armazem_supabase_salva_le_e_apaga():
    handler, linhas, pedidos = _postgrest_falso()
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    armazem = ArmazemSupabase(http, "http://rest:3000/", "chave-de-servico")

    async def fluxo():
        assert await armazem.obter("uid-ana") is None
        await armazem.salvar("uid-ana", {"token_acesso": "cifrado", "status": "conectado", "bot_id": None})
        doc = await armazem.obter("uid-ana")
        # o id e o carimbo de data ficam de fora do documento que o servico enxerga
        assert doc == {"token_acesso": "cifrado", "status": "conectado", "bot_id": None}
        # salvar de novo o documento lido nao pode mandar colunas internas
        await armazem.salvar("uid-ana", {**doc, "status": "reconectar", "atualizado_em": "x"})
        assert linhas["uid-ana"]["status"] == "reconectar" and "atualizado_em" not in linhas["uid-ana"]
        await armazem.apagar("uid-ana")
        assert await armazem.obter("uid-ana") is None

    asyncio.run(fluxo())
    assert all(p.headers["apikey"] == "chave-de-servico" and p.headers["Authorization"] == "Bearer chave-de-servico" for p in pedidos)
    assert all(str(p.url).startswith("http://rest:3000/usuarios") for p in pedidos)
    upsert = next(p for p in pedidos if p.method == "POST")
    assert "merge-duplicates" in upsert.headers["Prefer"] and upsert.url.params["on_conflict"] == "id"
