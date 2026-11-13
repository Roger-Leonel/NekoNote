from pathlib import Path
from fastapi import APIRouter
from fastapi.responses import FileResponse

router = APIRouter()

PASTA_HTML = Path(__file__).parent / "html"


@router.get("/", include_in_schema=False)
async def inicio():
    return FileResponse(PASTA_HTML / "index.html")


@router.get("/privacidade", include_in_schema=False)
async def privacidade():
    return FileResponse(PASTA_HTML / "privacidade.html")


@router.get("/termos", include_in_schema=False)
async def termos():
    return FileResponse(PASTA_HTML / "termos.html")


@router.get("/estilo.css", include_in_schema=False)
async def estilo():
    return FileResponse(PASTA_HTML / "estilo.css", media_type="text/css")
