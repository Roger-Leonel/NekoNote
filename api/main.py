import os
import sys

import uvicorn

sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))

from iniciar.app import app

if __name__ == "__main__":
    PORT = int(os.environ.get("PORT", "8000"))
    # no Docker o container precisa escutar em todas as interfaces; quem decide quem chega ate ele e o compose
    # (porta publicada so em 127.0.0.1, acesso externo apenas pelo tunel da Cloudflare)
    HOST = os.environ.get("HOST", "0.0.0.0")
    print(f"\nIniciando servidor FastAPI na porta {PORT}")
    print(f"URL local: http://localhost:{PORT}")
    uvicorn.run(
        app, host=HOST, port=PORT, reload=False,
        # 16 MB por mensagem porque o app manda varias fotos de uma vez
        ws_max_size=16 * 1024 * 1024,
        # o IP do cliente e resolvido pela propria API (protecao/ip.py), so de proxy confiavel
        proxy_headers=False,
        # o log de acesso proprio (middleware/requisicao.py) nao grava query string; o do uvicorn gravaria
        # o `code` e o `state` do OAuth do Notion
        access_log=False,
        server_header=False,
        # conexoes lentas/penduradas e excesso de conexoes simultaneas
        timeout_keep_alive=5,
        limit_concurrency=200,
        backlog=256,
        h11_max_incomplete_event_size=16 * 1024,
    )
