"""Processamentos em andamento, desligados da conexao WebSocket.

Um pedido custa OCR, uma chamada paga a IA e cria uma pagina no Notion. Se a conexao cai no meio e o app
reconecta (ele tenta ate 3 vezes), reprocessar geraria gasto em dobro e pagina duplicada. Por isso cada pedido tem
um `id_pedido` (escolhido pelo app, igual nas tentativas) e roda como tarefa propria:

- mesmo `id_pedido` + mesmo conteudo: a nova conexao se encaixa na tarefa (recebe o historico e o que vier);
- pedido ja concluido: devolve o resultado guardado, sem refazer nada (por `TTL_SEGUNDOS`);
- mesmo `id_pedido` com conteudo diferente: o chamador recusa (erro do cliente, nao reaproveita resposta alheia);
- ninguem acompanhando por `GRACA_SEGUNDOS`: a tarefa e cancelada (o usuario cancelou ou desistiu).
"""
import asyncio
import logging
import time
from typing import Awaitable, Callable

logger = logging.getLogger("nekonote.tarefas")

GRACA_SEGUNDOS = 30.0
# a mais longa cadeia de tentativas do app e de poucos minutos; 1 h cobre qualquer reenvio
TTL_SEGUNDOS = 3600.0
MAX_TAREFAS = 2000
EVENTOS_FINAIS = ("resultado", "erro")

Emitir = Callable[[dict], Awaitable[None]]
Enviar = Callable[[dict], Awaitable[None]]


class Tarefa:

    def __init__(self, assinatura: str, graca: float = GRACA_SEGUNDOS):
        self.assinatura = assinatura
        self.historico: list[dict] = []
        self.concluida_em: float | None = None
        self._filas: set[asyncio.Queue] = set()
        self._graca = graca
        self._timer: asyncio.TimerHandle | None = None
        self.task: asyncio.Task | None = None

    @property
    def concluida(self) -> bool:
        return self.concluida_em is not None

    def emitir(self, evento: dict) -> None:
        self.historico.append(evento)
        if evento.get("tipo") in EVENTOS_FINAIS:
            self.concluida_em = time.monotonic()
        for fila in self._filas:
            fila.put_nowait(evento)

    async def assistir(self, enviar: Enviar) -> None:
        """Manda ao cliente o que ja aconteceu e depois o que vier, ate o evento final ou o cliente sair."""
        fila: asyncio.Queue = asyncio.Queue()
        passado = list(self.historico)
        self._filas.add(fila)
        if self._timer:
            self._timer.cancel()
            self._timer = None
        try:
            for evento in passado:
                await enviar(evento)
            if any(e.get("tipo") in EVENTOS_FINAIS for e in passado):
                return
            while True:
                evento = await fila.get()
                await enviar(evento)
                if evento.get("tipo") in EVENTOS_FINAIS:
                    return
        except Exception:
            # o cliente saiu (WebSocketDisconnect, conexao fechada): a tarefa segue, quem reconectar a encontra
            logger.info("cliente saiu antes do fim do processamento")
        finally:
            self._filas.discard(fila)
            if not self.concluida and not self._filas and self.task is not None:
                self._timer = asyncio.get_running_loop().call_later(self._graca, self._cancelar_se_abandonada)

    def _cancelar_se_abandonada(self) -> None:
        if not self.concluida and not self._filas and self.task is not None and not self.task.done():
            logger.info("processamento cancelado: ninguem acompanhando")
            self.task.cancel()


class Tarefas:

    def __init__(self, ttl: float = TTL_SEGUNDOS, graca: float = GRACA_SEGUNDOS):
        self._ttl = ttl
        self._graca = graca
        self._por_chave: dict[tuple[str, str], Tarefa] = {}

    def obter(self, id_usuario: str, id_pedido: str) -> Tarefa | None:
        self._podar()
        return self._por_chave.get((id_usuario, id_pedido))

    def iniciar(self, id_usuario: str, id_pedido: str, assinatura: str,
                trabalho: Callable[[Emitir], Awaitable[dict]], ao_terminar: Callable[[], None]) -> Tarefa:
        """`trabalho` roda desligado da conexao e devolve o evento final; `ao_terminar` libera o que a tarefa segurava."""
        chave = (id_usuario, id_pedido)
        tarefa = Tarefa(assinatura, self._graca)

        async def emitir(evento: dict) -> None:
            tarefa.emitir(evento)

        async def rodar() -> None:
            try:
                tarefa.emitir(await trabalho(emitir))
            except asyncio.CancelledError:
                self._por_chave.pop(chave, None)  # cancelada: o app pode pedir de novo
                raise
            except Exception:
                logger.exception("falha inesperada no processamento (usuario %s)", id_usuario)
                tarefa.emitir({"tipo": "erro", "status": "error", "mensagem": "Erro interno ao processar as imagens. Tente novamente."})
            finally:
                ao_terminar()

        self._por_chave[chave] = tarefa
        tarefa.task = asyncio.get_running_loop().create_task(rodar())
        return tarefa

    def _podar(self) -> None:
        agora = time.monotonic()
        velhas = [c for c, t in self._por_chave.items() if t.concluida_em is not None and agora - t.concluida_em > self._ttl]
        for chave in velhas:
            del self._por_chave[chave]
        while len(self._por_chave) > MAX_TAREFAS:
            del self._por_chave[next(iter(self._por_chave))]
