import asyncio
import io
import shutil

import pytest
from PIL import Image, ImageDraw, ImageFont

from configuracao import Configuracao
from ocr.processar import ImagemInvalida, OcrIndisponivel, extrair_texto
from tests.conftest import auth, conectar_notion
from tests.test_websocket import pedido, receber_ate_fim


def test_ocr_fora_do_ar_da_mensagem_clara(cliente, ctx, ia_falsa):
    conectar_notion(cliente)

    async def quebrado(dados, cfg):
        raise OcrIndisponivel("Tesseract não encontrado.")

    ctx.ocr = quebrado
    with cliente.websocket_connect("/ws/ocr", headers=auth()) as ws:
        ws.send_json(pedido())
        eventos = receber_ate_fim(ws)
    assert "indisponível" in eventos[-1]["mensagem"] and not ia_falsa.chamadas


def test_ocr_recusa_arquivo_que_nao_e_imagem():
    with pytest.raises(ImagemInvalida):
        asyncio.run(extrair_texto(b"isto nao e uma imagem", Configuracao()))


# ---------- OCR de verdade (so roda onde o Tesseract esta instalado, ex.: dentro da imagem Docker) ----------

def _foto_com_texto(texto: str) -> bytes:
    fonte = None
    for caminho in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/arial.ttf"):
        try:
            fonte = ImageFont.truetype(caminho, 56)
            break
        except OSError:
            continue
    if fonte is None:
        fonte = ImageFont.load_default(size=56)  # fonte embutida no Pillow
    imagem = Image.new("RGB", (1400, 420), "white")
    desenho = ImageDraw.Draw(imagem)
    for i, linha in enumerate(texto.split("\n")):
        desenho.text((40, 40 + i * 100), linha, fill="black", font=fonte)
    saida = io.BytesIO()
    imagem.save(saida, "JPEG", quality=85)
    return saida.getvalue()


@pytest.mark.skipif(shutil.which("tesseract") is None, reason="Tesseract não instalado nesta máquina")
def test_ocr_le_texto_de_uma_foto():
    texto = asyncio.run(extrair_texto(_foto_com_texto("Revolucao Industrial\nMaquina a vapor em 1769"), Configuracao()))
    # a fonte embutida do Pillow confunde alguns digitos; as palavras tem que vir certas
    assert "Industrial" in texto and "vapor" in texto and "Maquina" in texto
