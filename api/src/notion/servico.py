import asyncio
import time
from typing import Awaitable, Callable, TypeVar

from armazenamento import ArmazemUsuarios
from configuracao import Configuracao
from notion.cliente import ClienteNotion, ErroNotion, NotionNaoAutorizado
from notion.blocos import corpo_da_pagina
from notion.leitura import eh_nota_neko, em_banco, nota_para_app, pagina_para_app, pai_bloco
from seguranca import Cifra, criar_state, validar_state

T = TypeVar("T")


class NaoConectado(Exception):
    pass


class PrecisaReconectar(Exception):
    pass


class StateInvalido(Exception):
    pass


class ServicoNotion:
    PAUSA_ENTRE_LOTES = 0.4

    def __init__(self, config: Configuracao, armazem: ArmazemUsuarios, cifra: Cifra, cliente: ClienteNotion):
        self._config = config
        self._armazem = armazem
        self._cifra = cifra
        self._cliente = cliente
        self._travas: dict[str, asyncio.Lock] = {}
        self._cache: dict[tuple[str, str], tuple[float, object]] = {}

    def url_autorizacao(self, id_usuario: str) -> str:
        state = criar_state(self._config.segredo_state, id_usuario, self._config.state_validade_segundos)
        return self._cliente.url_autorizacao(state)

    async def concluir_conexao(self, codigo: str, state: str) -> str:
        id_usuario = validar_state(self._config.segredo_state, state)
        if id_usuario is None:
            raise StateInvalido()
        dados = await self._cliente.trocar_codigo(codigo)
        espaco_icone = dados.get("workspace_icon")
        await self._armazem.salvar(
            id_usuario,
            {
                "token_acesso": self._cifra.cifrar(dados["access_token"]),
                "token_renovacao": self._cifra.cifrar(dados["refresh_token"]) if dados.get("refresh_token") else None,
                "bot_id": dados.get("bot_id"),
                "espaco_id": dados.get("workspace_id"),
                "espaco_nome": dados.get("workspace_name"),
                "espaco_icone": espaco_icone,
                "status": "conectado",
            },
        )
        self._limpar_cache(id_usuario)
        return id_usuario

    async def desconectar(self, id_usuario: str) -> None:
        doc = await self._armazem.obter(id_usuario)
        if doc and doc.get("cadastro_concluido"):
            # quem ja terminou o cadastro continua com a conta: so os dados do Notion saem, a marca fica
            await self._armazem.salvar(id_usuario, {
                "token_acesso": None, "token_renovacao": None, "bot_id": None,
                "espaco_id": None, "espaco_nome": None, "espaco_icone": None, "status": "desconectado",
            })
        else:
            await self._armazem.apagar(id_usuario)
        self._limpar_cache(id_usuario)

    async def cadastro_concluido(self, id_usuario: str) -> bool:
        doc = await self._armazem.obter(id_usuario)
        return bool(doc and doc.get("cadastro_concluido"))

    # o usuario tocou em "Finalizar": so agora a conta deixa de ser provisoria (exige o notion conectado)
    async def finalizar_cadastro(self, id_usuario: str) -> None:
        doc = await self._carregar(id_usuario)
        doc["cadastro_concluido"] = True
        await self._armazem.salvar(id_usuario, doc)

    async def status(self, id_usuario: str) -> dict:
        doc = await self._armazem.obter(id_usuario)
        if not doc:
            return {"status": "desconectado"}
        resumo = {
            "status": doc.get("status", "conectado"),
            "espaco_nome": doc.get("espaco_nome"),
            "espaco_icone": doc.get("espaco_icone"),
            "paginas": 0,
        }
        if resumo["status"] == "conectado":
            try:
                resumo["paginas"] = len(await self.listar_paginas(id_usuario))
            except (PrecisaReconectar, ErroNotion):
                resumo["status"] = (await self._armazem.obter(id_usuario) or {}).get("status", "conectado")
        return resumo

    def _trava(self, id_usuario: str) -> asyncio.Lock:
        return self._travas.setdefault(id_usuario, asyncio.Lock())

    async def _carregar(self, id_usuario: str) -> dict:
        doc = await self._armazem.obter(id_usuario)
        if not doc or not doc.get("token_acesso"):
            raise NaoConectado()
        if doc.get("status") == "reconectar":
            raise PrecisaReconectar()
        return doc

    # so uma renovacao por vez pra cada usuario
    async def _renovar(self, id_usuario: str, token_antigo: str) -> str:
        async with self._trava(id_usuario):
            doc = await self._carregar(id_usuario)
            atual = self._cifra.decifrar(doc["token_acesso"])
            if atual != token_antigo:
                return atual
            if not doc.get("token_renovacao"):
                await self._marcar_reconectar(id_usuario, doc)
                raise PrecisaReconectar()
            try:
                novos = await self._cliente.renovar(self._cifra.decifrar(doc["token_renovacao"]))
            except ErroNotion:
                await self._marcar_reconectar(id_usuario, doc)
                raise PrecisaReconectar()
            doc["token_acesso"] = self._cifra.cifrar(novos["access_token"])
            if novos.get("refresh_token"):
                doc["token_renovacao"] = self._cifra.cifrar(novos["refresh_token"])
            await self._armazem.salvar(id_usuario, doc)
            return novos["access_token"]

    async def _marcar_reconectar(self, id_usuario: str, doc: dict) -> None:
        doc["status"] = "reconectar"
        await self._armazem.salvar(id_usuario, doc)
        self._limpar_cache(id_usuario)

    # se o notion responder 401 renova o token e tenta de novo
    async def executar(self, id_usuario: str, operacao: Callable[[str], Awaitable[T]]) -> T:
        doc = await self._carregar(id_usuario)
        token = self._cifra.decifrar(doc["token_acesso"])
        try:
            return await operacao(token)
        except NotionNaoAutorizado:
            token = await self._renovar(id_usuario, token)
            try:
                return await operacao(token)
            except NotionNaoAutorizado:
                await self._marcar_reconectar(id_usuario, await self._carregar(id_usuario))
                raise PrecisaReconectar()

    async def token_valido(self, id_usuario: str) -> str:
        return await self.executar(id_usuario, self._confirmar)

    async def _confirmar(self, token: str) -> str:
        await self._cliente.eu(token)
        return token

    async def pagina_acessivel(self, id_usuario: str, id_pagina: str) -> bool:
        try:
            await self.executar(id_usuario, lambda token: self._cliente.obter_pagina(token, id_pagina))
            return True
        except ErroNotion as erro:
            if erro.status in (403, 404):
                return False
            raise

    # Cria a pagina da nota com o primeiro lote de blocos e anexa o resto aos poucos (o notion aceita 100 blocos por
    # pedido e ~3 pedidos por segundo). Devolve {"id", "url"}. A criacao passa por `executar`: se o token vencer, renova.
    async def criar_nota(self, id_usuario: str, id_pagina: str, titulo: str, icone: str, lotes: list[list[dict]]) -> dict:
        corpo = corpo_da_pagina(id_pagina, titulo, icone, lotes[0])
        pagina = await self.executar(id_usuario, lambda token: self._cliente.criar_pagina(token, corpo))
        for lote in lotes[1:]:
            await asyncio.sleep(self.PAUSA_ENTRE_LOTES)
            await self.executar(id_usuario, lambda token, lote=lote: self._cliente.anexar_blocos(token, pagina["id"], lote))
        self._limpar_cache(id_usuario)
        return {"id": pagina["id"], "url": pagina.get("url")}

    def _limpar_cache(self, id_usuario: str) -> None:
        for chave in [c for c in self._cache if c[0] == id_usuario]:
            del self._cache[chave]

    # cache curto pra nao estourar o limite de requisicoes do notion
    async def _com_cache(self, id_usuario: str, nome: str, produzir: Callable[[], Awaitable[T]]) -> T:
        agora = time.monotonic()
        chave = (id_usuario, nome)
        existente = self._cache.get(chave)
        if existente and existente[0] > agora:
            return existente[1]
        valor = await produzir()
        self._cache[chave] = (agora + self._config.cache_notion_segundos, valor)
        return valor

    async def _id_bot(self, id_usuario: str) -> str | None:
        """Id do usuario-bot da integracao neste espaco: as paginas criadas por ele sao as notas do NekoNote."""
        async def produzir() -> str | None:
            return (await self.executar(id_usuario, self._cliente.eu)).get("id")

        return await self._com_cache(id_usuario, "bot", produzir)

    async def listar_paginas(self, id_usuario: str) -> list[dict]:
        """So paginas, cada uma com o id do pai pra montar a hierarquia.

        Ficam de fora os bancos de dados (tabelas, calendarios...), as linhas deles e tudo que estiver dentro delas.
        """
        async def produzir() -> list[dict]:
            brutas = await self.executar(id_usuario, self._cliente.buscar_paginas)
            id_bot = await self._id_bot(id_usuario)
            candidatas = [p for p in brutas if not eh_nota_neko(p, id_bot) and not p.get("archived")]
            donos = await self._donos_dos_blocos(id_usuario, {pai_bloco(p) for p in candidatas} - {None})
            itens = [pagina_para_app(p, donos.get(pai_bloco(p))) for p in candidatas]

            excluidos = {p["id"] for p in candidatas if em_banco(p)}
            mudou = True
            while mudou:
                mudou = False
                for item in itens:
                    if item["id"] not in excluidos and item["pai"] in excluidos:
                        excluidos.add(item["id"])
                        mudou = True
            return [item for item in itens if item["id"] not in excluidos]

        return await self._com_cache(id_usuario, "paginas", produzir)

    # Algumas paginas ficam dentro de um bloco (ex.: coluna ou toggle). Sobe de bloco em bloco
    # ate achar a pagina dona, pra elas entrarem no lugar certo da hierarquia.
    async def _donos_dos_blocos(self, id_usuario: str, ids_blocos: set[str]) -> dict[str, str | None]:
        donos: dict[str, str | None] = {}
        limite = asyncio.Semaphore(3)

        async def dono(id_bloco: str, profundidade: int = 0) -> str | None:
            if id_bloco in donos:
                return donos[id_bloco]
            if profundidade >= 6:
                return None
            try:
                async with limite:
                    bloco = await self.executar(id_usuario, lambda t: self._cliente.obter_bloco(t, id_bloco))
            except ErroNotion:
                donos[id_bloco] = None
                return None
            pai = bloco.get("parent") or {}
            tipo = pai.get("type")
            if tipo == "page_id":
                resultado = pai["page_id"]
            elif tipo == "block_id":
                resultado = await dono(pai["block_id"], profundidade + 1)
            else:
                resultado = None
            donos[id_bloco] = resultado
            return resultado

        await asyncio.gather(*(dono(b) for b in ids_blocos))
        return donos

    async def listar_notas(self, id_usuario: str) -> list[dict]:
        async def produzir() -> list[dict]:
            # as notas sao as mais recentes, entao a primeira leva de 100 basta
            brutas = await self.executar(id_usuario, lambda t: self._cliente.buscar_paginas(t, maximo=100))
            id_bot = await self._id_bot(id_usuario)
            notas = [p for p in brutas if eh_nota_neko(p, id_bot) and not p.get("archived")][: self._config.max_notas]
            limite = asyncio.Semaphore(3)

            async def montar(pagina: dict) -> dict:
                async with limite:
                    blocos = await self.executar(id_usuario, lambda t: self._cliente.listar_blocos(t, pagina["id"]))
                return nota_para_app(pagina, blocos)

            return list(await asyncio.gather(*(montar(p) for p in notas)))

        return await self._com_cache(id_usuario, "notas", produzir)
