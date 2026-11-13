from notion.leitura import nota_para_app


def bloco(tipo: str, texto: str) -> dict:
    return {"type": tipo, tipo: {"rich_text": [{"plain_text": texto}]}}


def test_slides_da_apresentacao_nao_entram_nos_topicos():
    pagina = {"id": "p1", "properties": {}, "created_time": "2026-10-21T10:00:00Z", "url": "https://notion.so/p1"}
    blocos = [
        bloco("heading_2", "Tópicos"), bloco("bulleted_list_item", "Métodos"),
        bloco("heading_2", "Apresentação"), bloco("heading_3", "Slide 1: Introdução"),
        bloco("bulleted_list_item", "Ponto do slide"), bloco("quote", "Fala do slide"),
    ]
    nota = nota_para_app(pagina, blocos)
    assert nota["topicos"] == ["Métodos"]
