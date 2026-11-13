import asyncio
from typing import Protocol

import httpx


class ArmazemUsuarios(Protocol):
    async def obter(self, id_usuario: str) -> dict | None: ...
    async def salvar(self, id_usuario: str, dados: dict) -> None: ...
    async def apagar(self, id_usuario: str) -> None: ...


class ArmazemMemoria:

    def __init__(self):
        self._dados: dict[str, dict] = {}

    async def obter(self, id_usuario: str) -> dict | None:
        doc = self._dados.get(id_usuario)
        return dict(doc) if doc is not None else None

    async def salvar(self, id_usuario: str, dados: dict) -> None:
        # mesmo comportamento do upsert do supabase: so troca as colunas enviadas
        self._dados[id_usuario] = {**self._dados.get(id_usuario, {}), **dados}

    async def apagar(self, id_usuario: str) -> None:
        self._dados.pop(id_usuario, None)


# so os tokens do notion ficam no supabase (tabela public.usuarios, acessada pelo postgrest)
class ArmazemSupabase:

    TABELA = "usuarios"
    # colunas que nao fazem parte do documento que o servico enxerga
    _INTERNAS = ("id", "atualizado_em")

    def __init__(self, http: httpx.AsyncClient, url: str, chave_servico: str):
        self._http = http
        self._url = f"{url.rstrip('/')}/{self.TABELA}"
        self._cabecalhos = {"apikey": chave_servico, "Authorization": f"Bearer {chave_servico}"}

    async def obter(self, id_usuario: str) -> dict | None:
        resp = await self._http.get(
            self._url, params={"id": f"eq.{id_usuario}", "select": "*", "limit": "1"}, headers=self._cabecalhos
        )
        resp.raise_for_status()
        linhas = resp.json()
        if not linhas:
            return None
        return {k: v for k, v in linhas[0].items() if k not in self._INTERNAS}

    async def salvar(self, id_usuario: str, dados: dict) -> None:
        corpo = {**{k: v for k, v in dados.items() if k not in self._INTERNAS}, "id": id_usuario}
        resp = await self._http.post(
            self._url,
            json=corpo,
            params={"on_conflict": "id"},
            headers={**self._cabecalhos, "Prefer": "resolution=merge-duplicates,return=minimal"},
        )
        resp.raise_for_status()

    async def apagar(self, id_usuario: str) -> None:
        resp = await self._http.delete(self._url, params={"id": f"eq.{id_usuario}"}, headers=self._cabecalhos)
        resp.raise_for_status()
