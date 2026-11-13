"""Apaga as contas de quem comecou o cadastro e nao terminou.

O login com Google cria a conta no instante em que a pessoa escolhe o e-mail. O app ja apaga tudo quando a
pessoa sai no meio do cadastro; esta faxina e a garantia para quando o app e fechado antes de conseguir limpar.
"""
import asyncio
import logging

import httpx

from configuracao import Configuracao

logger = logging.getLogger("nekonote.limpeza")

MINUTOS_DE_TOLERANCIA = 30
INTERVALO_SEGUNDOS = 600
ATRASO_INICIAL_SEGUNDOS = 120


async def limpar_cadastros_incompletos(config: Configuracao, http: httpx.AsyncClient, armazem, remover_usuario,
                                       minutos: int = MINUTOS_DE_TOLERANCIA) -> int:
    resp = await http.post(
        f"{config.supabase_url.rstrip('/')}/rpc/cadastros_incompletos",
        json={"minutos": minutos},
        headers={"apikey": config.supabase_chave_servico, "Authorization": f"Bearer {config.supabase_chave_servico}"},
    )
    resp.raise_for_status()
    ids = resp.json()
    for id_usuario in ids:
        await armazem.apagar(id_usuario)
        await remover_usuario(id_usuario)
    return len(ids)


async def faxina_periodica(config: Configuracao, http: httpx.AsyncClient, armazem, remover_usuario) -> None:
    await asyncio.sleep(ATRASO_INICIAL_SEGUNDOS)
    while True:
        try:
            apagadas = await limpar_cadastros_incompletos(config, http, armazem, remover_usuario)
            if apagadas:
                logger.info("cadastros incompletos apagados: %d", apagadas)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("falha na faxina de cadastros incompletos")
        await asyncio.sleep(INTERVALO_SEGUNDOS)
