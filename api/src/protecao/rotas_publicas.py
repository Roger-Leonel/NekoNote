"""Unica lista de rotas que respondem sem login. Todo o resto exige token (negado por padrao).

Entrar aqui e uma decisao de seguranca: so rotas que o app ainda nao consegue autenticar (login) ou que sao
paginas estaticas/saude. Rotas novas nascem protegidas, sem ninguem precisar lembrar de proteger.
"""

# (metodos, caminho). Caminho terminando em "/*" casa o prefixo.
PUBLICAS: tuple[tuple[frozenset[str], str], ...] = (
    (frozenset({"GET"}), "/"),
    (frozenset({"GET"}), "/saude"),
    (frozenset({"GET"}), "/privacidade"),
    (frozenset({"GET"}), "/termos"),
    (frozenset({"GET"}), "/estilo.css"),
    # volta do OAuth do Notion: quem chama e o navegador do usuario, protegido pelo `state` assinado (HMAC + validade)
    (frozenset({"GET"}), "/notion/callback"),
    # login: ainda nao existe token. O proxy repassa so as rotas do app e o GoTrue confere as credenciais
    (frozenset({"GET", "POST", "PUT", "DELETE"}), "/auth/v1/*"),
)


def casa(padrao: str, caminho: str) -> bool:
    if padrao.endswith("/*"):
        base = padrao[:-2]
        return caminho == base or caminho.startswith(base + "/")
    return caminho == padrao


def eh_publica(metodo: str, caminho: str) -> bool:
    return any(metodo in metodos and casa(padrao, caminho) for metodos, padrao in PUBLICAS)
