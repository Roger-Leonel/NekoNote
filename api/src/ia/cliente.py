import asyncio
import json
import logging

import httpx
from pydantic import ValidationError

from configuracao import Configuracao
from ia.erros import ErroIA
from ia.esquema import ESQUEMA_JSON, NotaGerada

registro = logging.getLogger("nekonote.ia")

TENTATIVAS = 2
ESPERA_ENTRE_TENTATIVAS = 2.0
# o roteiro de uma apresentacao longa e o maior caso; passou disso a resposta veio cortada
MAX_TOKENS_RESPOSTA = 12_000


class ClienteIA:
    """Chama o modelo da OpenAI (Chat Completions com saida estruturada). O prompt e o texto das fotos nunca vao pro log."""

    def __init__(self, config: Configuracao, http: httpx.AsyncClient):
        self._config = config
        self._http = http

    async def gerar_nota(self, instrucoes: str, texto_usuario: str) -> NotaGerada:
        corpo = {
            "model": self._config.openai_modelo,
            "temperature": 0.2,
            "max_completion_tokens": MAX_TOKENS_RESPOSTA,
            "messages": [
                {"role": "system", "content": instrucoes},
                {"role": "user", "content": texto_usuario},
            ],
            "response_format": {"type": "json_schema", "json_schema": {"name": "nota", "strict": True, "schema": ESQUEMA_JSON}},
        }
        return self._interpretar(await self._chamar(corpo))

    async def _chamar(self, corpo: dict) -> dict:
        for tentativa in range(1, TENTATIVAS + 1):
            ultima = tentativa == TENTATIVAS
            try:
                resp = await self._http.post(
                    f"{self._config.openai_url_base.rstrip('/')}/chat/completions",
                    json=corpo,
                    headers={"Authorization": f"Bearer {self._config.openai_chave}"},
                    timeout=self._config.openai_timeout_segundos,
                )
            except httpx.TimeoutException:
                registro.warning("OpenAI: tempo esgotado (tentativa %d)", tentativa)
                if ultima:
                    raise ErroIA("A geração do resumo demorou demais. Tente novamente.")
            except httpx.HTTPError as erro:
                registro.warning("OpenAI: falha de rede (%s, tentativa %d)", type(erro).__name__, tentativa)
                if ultima:
                    raise ErroIA("Não foi possível falar com o serviço de resumos. Tente novamente.")
            else:
                if resp.status_code == 200:
                    try:
                        return resp.json()
                    except ValueError:
                        raise ErroIA("A IA devolveu uma resposta inválida.")
                codigo = _codigo_do_erro(resp)
                registro.warning("OpenAI: status %d (%s, tentativa %d)", resp.status_code, codigo, tentativa)
                # 429 (limite/fila) e 5xx passam; o resto (chave errada, pedido invalido) nao muda tentando de novo
                if resp.status_code not in (429, 500, 502, 503, 504) or ultima:
                    raise ErroIA(_mensagem_para_usuario(resp.status_code, codigo))
            await asyncio.sleep(ESPERA_ENTRE_TENTATIVAS)
        raise ErroIA("Não foi possível gerar o resumo agora.")  # inalcancavel; deixa o tipo certo

    @staticmethod
    def _interpretar(resposta: dict) -> NotaGerada:
        # resposta de servico externo: so se usa o que tiver o formato esperado
        try:
            escolha = resposta["choices"][0]
            mensagem = escolha["message"]
        except (KeyError, IndexError, TypeError):
            raise ErroIA("A IA devolveu uma resposta inválida.")
        if mensagem.get("refusal"):
            raise ErroIA("A IA não conseguiu processar este conteúdo.")
        if escolha.get("finish_reason") == "length":
            raise ErroIA("O resumo ficou grande demais. Envie menos páginas por vez.")
        try:
            return NotaGerada.model_validate(json.loads(mensagem.get("content") or ""))
        except (ValueError, ValidationError):
            raise ErroIA("A IA devolveu uma resposta inválida.")


def _codigo_do_erro(resp: httpx.Response) -> str:
    try:
        return str(resp.json().get("error", {}).get("code") or "")
    except (ValueError, AttributeError):
        return ""


def _mensagem_para_usuario(status: int, codigo: str) -> str:
    if status == 429 and codigo == "insufficient_quota":
        return "O serviço de resumos está sem créditos no momento. Tente mais tarde."
    if status in (401, 403):
        return "O serviço de resumos está indisponível. Tente mais tarde."
    if status == 429:
        return "O serviço de resumos está muito ocupado. Tente novamente em instantes."
    return "Não foi possível gerar o resumo agora. Tente novamente."
