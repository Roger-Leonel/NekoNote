FROM python:3.13-slim

# Tesseract (OCR) com o idioma português
RUN apt-get update     && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-por     && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/
COPY main.py .

ENV PYTHONPATH=/app/src PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1

# nao roda como root: se alguem explorar uma falha, o estrago fica restrito ao app
RUN useradd --system --no-create-home --uid 10001 neko
USER neko

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/saude', timeout=4).status == 200 else 1)"

CMD ["python", "main.py"]
