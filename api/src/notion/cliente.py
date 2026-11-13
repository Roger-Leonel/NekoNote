import base64

import httpx

from configuracao import Configuracao

# teto de paginas lidas na busca (cada 100 e uma chamada; o notion limita a ~3 por segundo)
MAX_PAGINAS = 1000


class ErroNotion(Exception):
    def __init__(self, status: int, codigo: str = "", mensagem: str = ""):
        super().__init__(f"Notion {status} {codigo}: {mensagem}")
        self.status = status
        self.codigo = codigo
        self.mensagem = mensagem


class NotionNaoAutorizado(ErroNotion):
    pass


class ClienteNotion:
    def __init__(self, config: Configuracao, http: httpx.AsyncClient):
        self._config = config
        self._http = http

    def url_autorizacao(self, state: str) -> str:
        params = httpx.QueryParams(
            {
                "client_id": self._config.notion_client_id,
                "response_type": "code",
                "owner": "user",
                "redirect_uri": self._config.notion_redirect_uri,
                "state": state,
            }
        )
        return f"{self._config.notion_url_base}/v1/oauth/authorize?{params}"

    def _basic(self) -> str:
        bruto = f"{self._config.notion_client_id}:{self._config.notion_client_secret}".encode()
        return "Basic " + base64.b64encode(bruto).decode()

    async def _token(self, corpo: dict) -> dict:
        resp = await self._http.post(
            f"{self._config.notion_url_base}/v1/oauth/token",
            json=corpo,
            headers={"Authorization": self._basic(), "Notion-Version": self._config.notion_versao},
        )
        self._verificar(resp)
        return resp.json()

    async def trocar_codigo(self, codigo: str) -> dict:
        return await self._token(
            {"grant_type": "authorization_code", "code": codigo, "redirect_uri": self._config.notion_redirect_uri}
        )

    async def renovar(self, token_renovacao: str) -> dict:
        return await self._token({"grant_type": "refresh_token", "refresh_token": token_renovacao})

    def _cabecalhos(self, token: str) -> dict:
        return {"Authorization": f"Bearer {token}", "Notion-Version": self._config.notion_versao}

    @staticmethod
    def _verificar(resp: httpx.Response) -> None:
        if resp.status_code < 400:
            return
        try:
            corpo = resp.json()
        except ValueError:
            corpo = {}
        classe = NotionNaoAutorizado if resp.status_code == 401 else ErroNotion
        raise classe(resp.status_code, corpo.get("code", ""), corpo.get("message", resp.text[:200]))

    async def eu(self, token: str) -> dict:
        resp = await self._http.get(f"{self._config.notion_url_base}/v1/users/me", headers=self._cabecalhos(token))
        self._verificar(resp)
        return resp.json()

    # O notion devolve no maximo 100 por vez e manda um cursor pra pedir o resto, entao le em voltas
    # ate acabar ou chegar em `maximo` (a busca fica sempre mais recentes primeiro).
    async def _buscar(self, token: str, objeto: str, maximo: int) -> list[dict]:
        resultados: list[dict] = []
        cursor: str | None = None
        while len(resultados) < maximo:
            corpo: dict = {
                "filter": {"property": "object", "value": objeto},
                "sort": {"direction": "descending", "timestamp": "last_edited_time"},
                "page_size": min(100, maximo - len(resultados)),
            }
            if cursor:
                corpo["start_cursor"] = cursor
            resp = await self._http.post(
                f"{self._config.notion_url_base}/v1/search", headers=self._cabecalhos(token), json=corpo
            )
            self._verificar(resp)
            dados = resp.json()
            resultados.extend(dados.get("results", []))
            cursor = dados.get("next_cursor")
            if not dados.get("has_more") or not cursor:
                break
        return resultados

    async def buscar_paginas(self, token: str, maximo: int = MAX_PAGINAS) -> list[dict]:
        return await self._buscar(token, "page", maximo)

    async def obter_bloco(self, token: str, id_bloco: str) -> dict:
        resp = await self._http.get(
            f"{self._config.notion_url_base}/v1/blocks/{id_bloco}", headers=self._cabecalhos(token)
        )
        self._verificar(resp)
        return resp.json()

    async def obter_pagina(self, token: str, id_pagina: str) -> dict:
        resp = await self._http.get(
            f"{self._config.notion_url_base}/v1/pages/{id_pagina}", headers=self._cabecalhos(token)
        )
        self._verificar(resp)
        return resp.json()

    async def criar_pagina(self, token: str, corpo: dict) -> dict:
        resp = await self._http.post(
            f"{self._config.notion_url_base}/v1/pages", headers=self._cabecalhos(token), json=corpo, timeout=60
        )
        self._verificar(resp)
        return resp.json()

    async def anexar_blocos(self, token: str, id_bloco: str, filhos: list[dict]) -> None:
        resp = await self._http.patch(
            f"{self._config.notion_url_base}/v1/blocks/{id_bloco}/children",
            headers=self._cabecalhos(token),
            json={"children": filhos},
            timeout=60,
        )
        self._verificar(resp)

    async def listar_blocos(self, token: str, id_bloco: str, limite: int = 50) -> list[dict]:
        resp = await self._http.get(
            f"{self._config.notion_url_base}/v1/blocks/{id_bloco}/children",
            headers=self._cabecalhos(token),
            params={"page_size": limite},
        )
        self._verificar(resp)
        return resp.json().get("results", [])
