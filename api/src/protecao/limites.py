"""Limites e identificacao do cliente.

- `Limitador`: limite de taxa por janela deslizante, em memoria (3 numeros por chave; a memoria nao cresce com o
  trafego). Vale para UM processo; com varios workers o estado precisaria ir para o Redis.
- `Simultaneos`: quantos OCRs podem rodar ao mesmo tempo (por usuario e no servidor todo).
- `ip_do_cliente`: o IP real vem do cabecalho da Cloudflare, mas so quando quem conectou e um proxy confiavel.
- `Politica` / `Protecao`: a tabela de limites por rota e quem os aplica. Duas etapas: `ip` (antes de checar o
  token) e `usuario` (depois, ja com o dono conhecido). Uma requisicao pode casar varias politicas; todas precisam passar.
"""
import ipaddress
import math
import time
from dataclasses import dataclass
from typing import Callable

from configuracao import Configuracao
from protecao.rotas_publicas import casa


@dataclass(frozen=True)
class Decisao:
    permitido: bool
    limite: int
    restante: int
    # segundos ate poder tentar de novo (0 quando permitido)
    esperar: int = 0


class Limitador:

    def __init__(self, maximo: int, janela_segundos: float, max_chaves: int = 100_000,
                 relogio: Callable[[], float] = time.monotonic):
        if maximo < 1 or janela_segundos <= 0:
            raise ValueError("limite inválido")
        self.maximo = maximo
        self.janela = float(janela_segundos)
        self._max_chaves = max_chaves
        self._relogio = relogio
        # chave -> [indice da janela, contagem na janela, contagem na janela anterior]
        self._estado: dict[str, list[float]] = {}
        self._chamadas = 0

    def _situacao(self, chave: str, agora: float) -> tuple[int, float, float, float]:
        """(indice da janela, decorrido nela, contagem atual, contagem anterior) ja rolados para `agora`."""
        indice = int(agora // self.janela)
        decorrido = agora - indice * self.janela
        est = self._estado.get(chave)
        if est is None:
            return indice, decorrido, 0, 0
        if est[0] == indice:
            return indice, decorrido, est[1], est[2]
        if est[0] == indice - 1:
            return indice, decorrido, 0, est[1]
        return indice, decorrido, 0, 0

    def _estimativa(self, atual: float, anterior: float, decorrido: float) -> float:
        return anterior * (1 - decorrido / self.janela) + atual

    def _esperar(self, atual: float, anterior: float, decorrido: float, custo: int) -> int:
        folga = self.maximo - custo
        if atual <= folga:
            # ainda nesta janela a parte herdada da anterior deixa de pesar o suficiente
            if anterior <= 0:
                return 1
            quando = self.janela * (1 - (folga - atual) / anterior)
            return max(1, math.ceil(quando - decorrido))
        # janela atual cheia: espera virar a janela e a contagem dela vira a "anterior"
        ate_virar = self.janela - decorrido
        quando = self.janela * (1 - folga / atual) if atual > folga else 0
        return max(1, math.ceil(ate_virar + max(0.0, quando)))

    def consultar(self, chave: str, agora: float | None = None) -> Decisao:
        """Diz se a chave ja estourou o limite, sem contar uma nova ocorrencia."""
        agora = self._relogio() if agora is None else agora
        _, decorrido, atual, anterior = self._situacao(chave, agora)
        usado = self._estimativa(atual, anterior, decorrido)
        if usado >= self.maximo:
            return Decisao(False, self.maximo, 0, self._esperar(atual, anterior, decorrido, 1))
        return Decisao(True, self.maximo, max(0, int(self.maximo - usado)))

    def tentar(self, chave: str, custo: int = 1, agora: float | None = None) -> Decisao:
        agora = self._relogio() if agora is None else agora
        indice, decorrido, atual, anterior = self._situacao(chave, agora)
        usado = self._estimativa(atual, anterior, decorrido)
        if usado + custo > self.maximo:
            return Decisao(False, self.maximo, 0, self._esperar(atual, anterior, decorrido, custo))
        self._guardar(chave, [indice, atual + custo, anterior], agora)
        return Decisao(True, self.maximo, max(0, int(self.maximo - usado - custo)))

    def _guardar(self, chave: str, estado: list[float], agora: float) -> None:
        self._estado[chave] = estado
        self._chamadas += 1
        if len(self._estado) > self._max_chaves or self._chamadas % 2048 == 0:
            self._podar(agora)

    def _podar(self, agora: float) -> None:
        indice = int(agora // self.janela)
        for chave in [c for c, e in self._estado.items() if e[0] < indice - 1]:
            del self._estado[chave]
        while len(self._estado) > self._max_chaves:
            del self._estado[next(iter(self._estado))]


class Simultaneos:
    """Quantos processamentos pesados (OCR) podem rodar ao mesmo tempo: por usuario e no servidor todo."""

    def __init__(self, por_usuario: int = 1, total: int = 6):
        self._por_usuario = por_usuario
        self._total = total
        self._ativos: dict[str, int] = {}
        self._soma = 0

    def entrar(self, chave: str) -> bool:
        if self._soma >= self._total or self._ativos.get(chave, 0) >= self._por_usuario:
            return False
        self._ativos[chave] = self._ativos.get(chave, 0) + 1
        self._soma += 1
        return True

    def sair(self, chave: str) -> None:
        restante = self._ativos.get(chave, 0) - 1
        if restante > 0:
            self._ativos[chave] = restante
        else:
            self._ativos.pop(chave, None)
        self._soma = max(0, self._soma - 1)


DESCONHECIDO = "desconhecido"

# loopback + redes privadas (o tunel local e a rede do Docker)
REDES_PADRAO = ("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")


def parse_redes(texto: str | None) -> tuple:
    itens = [i.strip() for i in (texto or "").split(",") if i.strip()] or list(REDES_PADRAO)
    return tuple(ipaddress.ip_network(i, strict=False) for i in itens)


def _como_ip(valor: str | None):
    try:
        return ipaddress.ip_address((valor or "").strip())
    except ValueError:
        return None


def _chave(ip) -> str:
    # IPv6: um cliente costuma ter um /64 inteiro; contar por endereco deixaria trocar de IP a cada requisicao
    if ip.version == 6:
        return str(ipaddress.ip_network(f"{ip}/64", strict=False).network_address) + "/64"
    return str(ip)


def ip_do_cliente(scope: dict, redes_confiaveis: tuple) -> str:
    cliente = scope.get("client")
    direto = _como_ip(cliente[0]) if cliente else None
    if direto is None:
        return cliente[0] if cliente else DESCONHECIDO
    if any(direto in rede for rede in redes_confiaveis):
        for nome, valor in scope.get("headers", []):
            if nome == b"cf-connecting-ip":
                real = _como_ip(valor.decode("latin-1"))
                if real is not None:
                    return _chave(real)
    return _chave(direto)


MINUTO = 60
HORA = 3600


@dataclass(frozen=True)
class Politica:
    nome: str
    etapa: str  # "ip" | "usuario"
    maximo: int
    janela: int
    caminho: str = "/*"
    metodos: frozenset[str] | None = None  # None = qualquer metodo


def politicas_padrao(config: Configuracao) -> tuple[Politica, ...]:
    return (
        # --- por IP, antes do login ---
        Politica("ip.geral", "ip", config.limite_ip_por_minuto, MINUTO),
        Politica("ip.login", "ip", 20, MINUTO, "/auth/v1/*"),
        Politica("ip.oauth_callback", "ip", 20, MINUTO, "/notion/callback"),
        Politica("ip.websocket", "ip", 10, MINUTO, "/ws/ocr"),
        # --- por usuario, depois do login ---
        Politica("usuario.geral", "usuario", config.limite_usuario_por_minuto, MINUTO),
        Politica("usuario.notion_conectar", "usuario", 10, 10 * MINUTO, "/notion/conectar", frozenset({"POST"})),
        Politica("usuario.notion_leitura", "usuario", 30, MINUTO, "/notion/paginas", frozenset({"GET"})),
        Politica("usuario.notas", "usuario", 30, MINUTO, "/notas", frozenset({"GET"})),
        Politica("usuario.excluir_conta", "usuario", 3, HORA, "/conta", frozenset({"DELETE"})),
        Politica("usuario.websocket", "usuario", 10, MINUTO, "/ws/ocr"),
    )


class Protecao:

    def __init__(self, config: Configuracao, politicas: tuple[Politica, ...] | None = None):
        self.politicas = politicas if politicas is not None else politicas_padrao(config)
        self.redes_confiaveis = parse_redes(config.proxies_confiaveis)
        self._limitadores = {p.nome: Limitador(p.maximo, p.janela) for p in self.politicas}
        # tentativas de token invalido por IP: passou do teto, o IP fica bloqueado ate a janela esfriar
        self.falhas_auth = Limitador(config.max_falhas_auth, 5 * MINUTO)
        self.simultaneos = Simultaneos(por_usuario=1, total=config.max_processamentos_simultaneos)

    def verificar(self, etapa: str, metodo: str, caminho: str, chave: str) -> tuple[Decisao | None, Decisao | None]:
        """(primeira decisao negada ou None, decisao mais apertada que passou, para os cabecalhos RateLimit-*)."""
        mais_apertada: Decisao | None = None
        for politica in self.politicas:
            if politica.etapa != etapa or not casa(politica.caminho, caminho):
                continue
            if politica.metodos is not None and metodo not in politica.metodos:
                continue
            decisao = self._limitadores[politica.nome].tentar(chave)
            if not decisao.permitido:
                return decisao, None
            if mais_apertada is None or decisao.restante < mais_apertada.restante:
                mais_apertada = decisao
        return None, mais_apertada
