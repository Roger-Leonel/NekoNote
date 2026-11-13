import time
from collections import defaultdict, deque


class LimitePorUsuario:
    def __init__(self, maximo: int, janela_segundos: int = 3600):
        self._maximo = maximo
        self._janela = janela_segundos
        self._usos: dict[str, deque[float]] = defaultdict(deque)

    # false se o usuario passou do limite na ultima hora
    def tentar(self, id_usuario: str, agora: float | None = None) -> bool:
        agora = agora if agora is not None else time.time()
        fila = self._usos[id_usuario]
        while fila and fila[0] <= agora - self._janela:
            fila.popleft()
        if len(fila) >= self._maximo:
            return False
        fila.append(agora)
        return True
