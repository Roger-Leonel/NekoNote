import asyncio
import json

import httpx

from armazenamento import ArmazemMemoria
from configuracao import Configuracao
from limpeza import limpar_cadastros_incompletos


def test_faxina_apaga_conta_e_tokens_dos_cadastros_incompletos():
    config = Configuracao(supabase_url="http://rest:3000", supabase_chave_servico="chave-de-servico")
    chamadas = []

    def handler(req: httpx.Request) -> httpx.Response:
        chamadas.append(req)
        return httpx.Response(200, json=["uid-a", "uid-b"])

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    armazem = ArmazemMemoria()
    removidos: list[str] = []

    async def remover(uid: str) -> None:
        removidos.append(uid)

    async def fluxo():
        await armazem.salvar("uid-a", {"token_acesso": "x"})
        await armazem.salvar("uid-ok", {"token_acesso": "y", "cadastro_concluido": True})
        return await limpar_cadastros_incompletos(config, http, armazem, remover, minutos=15)

    assert asyncio.run(fluxo()) == 2
    assert removidos == ["uid-a", "uid-b"]
    assert asyncio.run(armazem.obter("uid-a")) is None
    assert asyncio.run(armazem.obter("uid-ok")) is not None
    assert str(chamadas[0].url) == "http://rest:3000/rpc/cadastros_incompletos"
    assert json.loads(chamadas[0].content) == {"minutos": 15}
    assert chamadas[0].headers["Authorization"] == "Bearer chave-de-servico"


def test_faxina_sem_nada_a_apagar():
    config = Configuracao(supabase_url="http://rest:3000", supabase_chave_servico="k")
    http = httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(200, json=[])))

    async def remover(uid: str) -> None:
        raise AssertionError("nao deveria remover ninguem")

    assert asyncio.run(limpar_cadastros_incompletos(config, http, ArmazemMemoria(), remover)) == 0
