import asyncio
import logging
from urllib.parse import urlparse

from autenticacao import Usuario
from contexto import Contexto
from ia.erros import ErroIA
from ia.prompt import montar_prompt
from notion.blocos import em_lotes, icone_da_nota, montar_blocos, titulo_da_nota
from notion.cliente import ErroNotion
from notion.servico import NaoConectado, PrecisaReconectar
from ocr.pedido import Pedido
from ocr.processar import ImagemInvalida, OcrIndisponivel
from ocr.tarefas import Emitir

registro = logging.getLogger("nekonote.processamento")

PERCENTUAL_OCR = 90
HOSTS_NOTION = ("notion.so", "notion.site")


def erro(mensagem: str) -> dict:
    return {"tipo": "erro", "status": "error", "mensagem": mensagem}


def _url_do_notion(valor: object) -> str | None:
    if not isinstance(valor, str):
        return None
    partes = urlparse(valor.strip())
    host = (partes.hostname or "").lower()
    if partes.scheme == "https" and any(host == h or host.endswith("." + h) for h in HOSTS_NOTION):
        return valor.strip()
    return None


async def processar(ctx: Contexto, usuario: Usuario, pedido: Pedido, emitir: Emitir) -> dict:
    """Fotos -> OCR -> IA -> pagina no Notion. Avisa cada etapa por `emitir` e devolve o evento final (resultado ou erro)."""
    total = len(pedido.imagens)
    try:
        try:
            # antes de tudo ve se o notion esta conectado e a pagina liberada
            await ctx.notion.token_valido(usuario.id)
            if not await ctx.notion.pagina_acessivel(usuario.id, pedido.id_pagina):
                return erro("A página escolhida não está liberada para o NekoNote. Reconecte o Notion e libere-a.")
        except NaoConectado:
            return erro("Conecte o Notion antes de enviar fotos.")
        except PrecisaReconectar:
            return erro("A conexão com o Notion expirou. Reconecte nas configurações.")
        except ErroNotion:
            return erro("Não foi possível falar com o Notion agora. Tente novamente.")

        # limite de processamentos por hora (conta uma vez por pedido, nao por reconexao)
        if not ctx.limite.tentar(usuario.id):
            return erro(f"Limite de {ctx.config.limite_processamentos_por_hora} processamentos por hora atingido. Tente mais tarde.")

        await emitir({"tipo": "inicio", "total": total, "mensagem": f"Iniciando processamento de {total} imagem(ns)..."})

        # faz o ocr de poucas imagens ao mesmo tempo
        limite = asyncio.Semaphore(max(1, ctx.config.ocr_paralelismo))

        async def ler(dados: bytes) -> str:
            async with limite:
                return await ctx.ocr(dados, ctx.config)

        tarefas = [asyncio.create_task(ler(d)) for d in pedido.imagens]
        textos: list[str] = []
        try:
            for indice, tarefa in enumerate(tarefas, 1):
                try:
                    textos.append(await tarefa)
                except ImagemInvalida:
                    return erro(f"A imagem {indice} não pôde ser lida.")
                except OcrIndisponivel as falha:
                    registro.error("OCR indisponível (usuário %s): %s", usuario.id, falha)
                    return erro("O leitor de texto está indisponível no servidor. Tente mais tarde.")
                await emitir({
                    "tipo": "progresso",
                    "percentual": int(indice / total * PERCENTUAL_OCR),
                    "imagem_atual": indice,
                    "total": total,
                    "mensagem": f"Processando imagem {indice} de {total}...",
                })
        finally:
            for t in tarefas:
                t.cancel()

        textos_uteis = [t for t in textos if t]
        if not textos_uteis:
            return erro("Não foi possível reconhecer texto nas imagens. Tente fotos mais nítidas.")

        # a IA escreve resumo/topicos/assuntos com todos os textos de uma vez
        await emitir({"tipo": "enviando", "mensagem": "Gerando resumos..."})
        modalidades = pedido.modalidades.como_dict()
        try:
            instrucoes, texto_usuario = montar_prompt(textos_uteis, modalidades, pedido.temperamento)
            nota = await ctx.ia.gerar_nota(instrucoes, texto_usuario)
        except ErroIA as falha:
            return erro(str(falha))
        blocos = montar_blocos(nota, modalidades)
        if not blocos:
            return erro("A IA não gerou conteúdo para as opções escolhidas. Tente novamente.")

        await emitir({"tipo": "criando", "mensagem": "Criando a página no Notion..."})
        try:
            pagina = await ctx.notion.criar_nota(
                usuario.id, pedido.id_pagina, titulo_da_nota(nota), icone_da_nota(nota), em_lotes(blocos))
        except ErroNotion as falha:
            if falha.status in (403, 404):
                return erro("A página escolhida não está liberada para o NekoNote. Reconecte o Notion e libere-a.")
            registro.warning("Notion recusou a criação da página: %s", falha.codigo or falha.status)
            return erro("Não foi possível criar a página no Notion agora. Tente novamente.")

        evento = {
            "tipo": "resultado",
            "status": "success",
            "total_processado": total,
            "mensagem": "Página criada com sucesso!",
        }
        url = _url_do_notion(pagina.get("url"))
        if url:
            evento["url_pagina"] = url
        return evento
    except (NaoConectado, PrecisaReconectar):
        return erro("A conexão com o Notion expirou. Reconecte nas configurações.")
    except ErroNotion:
        return erro("Não foi possível falar com o Notion agora. Tente novamente.")
