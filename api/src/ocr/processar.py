import asyncio
import io
import logging

from PIL import Image, ImageOps

from configuracao import Configuracao

registro = logging.getLogger("nekonote.ocr")

# acima disso o Pillow ja recusa (DecompressionBombError); 40 MP cobre qualquer foto de celular
Image.MAX_IMAGE_PIXELS = 40_000_000

# o app manda fotos de ate 2000 px; texto pequeno sai melhor do Tesseract com ~300 DPI, entao amplia as menores
LADO_MINIMO_PX = 1500
# uma pagina leva poucos segundos; passou disso o Tesseract travou e nao pode prender o servidor
TIMEOUT_SEGUNDOS = 60


class ImagemInvalida(Exception):
    pass


class OcrIndisponivel(Exception):
    """Tesseract ausente, sem o idioma configurado ou travado: problema do servidor, nao da foto."""


def _configurar(config: Configuracao):
    import pytesseract

    if config.tesseract_cmd:
        pytesseract.pytesseract.tesseract_cmd = config.tesseract_cmd
    return pytesseract


def _preparar(imagem: Image.Image) -> Image.Image:
    imagem = ImageOps.exif_transpose(imagem)
    imagem = ImageOps.autocontrast(imagem.convert("L"))
    menor_lado = min(imagem.size)
    if menor_lado < LADO_MINIMO_PX:
        fator = LADO_MINIMO_PX / menor_lado
        imagem = imagem.resize((round(imagem.width * fator), round(imagem.height * fator)), Image.Resampling.LANCZOS)
    return imagem


def _extrair_sincrono(dados: bytes, config: Configuracao) -> str:
    pytesseract = _configurar(config)
    try:
        imagem = Image.open(io.BytesIO(dados))
        imagem.load()
    except Exception as erro:
        raise ImagemInvalida("Arquivo de imagem inválido.") from erro
    imagem = _preparar(imagem)
    try:
        texto = pytesseract.image_to_string(imagem, lang=config.ocr_idioma, config="--psm 3", timeout=TIMEOUT_SEGUNDOS)
    except pytesseract.TesseractNotFoundError as erro:
        raise OcrIndisponivel("Tesseract não encontrado.") from erro
    except (pytesseract.TesseractError, RuntimeError) as erro:
        # RuntimeError: timeout do pytesseract. TesseractError: ex. idioma nao instalado
        raise OcrIndisponivel(str(erro)[:200]) from erro
    return texto.strip()


async def extrair_texto(dados: bytes, config: Configuracao) -> str:
    return await asyncio.to_thread(_extrair_sincrono, dados, config)


# chamado na subida do servidor: o problema aparece no log na hora, nao quando o primeiro usuario mandar foto
def verificar_ocr(config: Configuracao) -> bool:
    try:
        pytesseract = _configurar(config)
        versao = pytesseract.get_tesseract_version()
        idiomas = pytesseract.get_languages()
    except Exception as erro:
        registro.error("OCR indisponível (Tesseract não encontrado ou com erro): %s", type(erro).__name__)
        return False
    faltando = [i for i in config.ocr_idioma.split("+") if i not in idiomas]
    if faltando:
        registro.error("OCR sem o idioma %s (instalados: %s)", ", ".join(faltando), ", ".join(idiomas))
        return False
    registro.info("OCR pronto: Tesseract %s, idioma %s", versao, config.ocr_idioma)
    return True
