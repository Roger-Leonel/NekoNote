import os
from dataclasses import dataclass, field


def _env(nome: str, padrao: str = "") -> str:
    return os.environ.get(nome, padrao).strip()


def _inteiro_positivo(nome: str, padrao: int) -> int:
    try:
        valor = int(_env(nome, str(padrao)))
    except ValueError:
        return padrao
    return valor if valor >= 1 else padrao


@dataclass(frozen=True)
class Configuracao:
    # endereco publico da API (subdominio + dominio, sem https://). Daqui saem o redirect do Notion,
    # a URL do login e os hosts aceitos; o app usa o mesmo valor em nekonote.dominio
    dominio: str = field(default_factory=lambda: _env("API_DOMAIN", "api.korvon.com.br"))

    # IA: gera o resumo, os topicos e os assuntos a partir do texto das fotos (Chat Completions, saida estruturada)
    openai_chave: str = field(default_factory=lambda: _env("OPENAI_API_KEY"))
    openai_modelo: str = field(default_factory=lambda: _env("OPENAI_MODEL", "gpt-4.1-mini"))
    openai_url_base: str = field(default_factory=lambda: _env("OPENAI_URL_BASE", "https://api.openai.com/v1"))
    openai_timeout_segundos: float = field(default_factory=lambda: float(_env("OPENAI_TIMEOUT_SEGUNDOS", "120")))

    notion_client_id: str = field(default_factory=lambda: _env("NOTION_CLIENT_ID"))
    notion_client_secret: str = field(default_factory=lambda: _env("NOTION_CLIENT_SECRET"))
    notion_redirect_uri: str = ""
    notion_versao: str = field(default_factory=lambda: _env("NOTION_VERSAO", "2022-06-28"))
    notion_url_base: str = field(default_factory=lambda: _env("NOTION_URL_BASE", "https://api.notion.com"))

    chave_criptografia: str = field(default_factory=lambda: _env("ENCRYPTION_KEY"))
    segredo_state: str = field(default_factory=lambda: _env("STATE_SECRET"))
    state_validade_segundos: int = 600

    # login: o Supabase Auth (GoTrue) assina o token de acesso com este segredo
    jwt_secret: str = field(default_factory=lambda: _env("JWT_SECRET"))
    auth_url: str = field(default_factory=lambda: _env("SUPABASE_AUTH_URL", "http://auth:9999"))

    supabase_url: str = field(default_factory=lambda: _env("SUPABASE_URL", "http://rest:3000"))
    supabase_chave_servico: str = field(default_factory=lambda: _env("SUPABASE_SERVICE_KEY"))

    tesseract_cmd: str = field(default_factory=lambda: _env("TESSERACT_CMD"))
    ocr_idioma: str = field(default_factory=lambda: _env("OCR_IDIOMA", "por"))
    ocr_paralelismo: int = field(default_factory=lambda: int(_env("OCR_PARALLELISM", "2")))

    limite_processamentos_por_hora: int = field(default_factory=lambda: int(_env("PROCESSING_LIMIT_PER_HOUR", "10")))
    # limite de imagens por envio (MAX_IMAGES); valor invalido ou menor que 1 cai no padrao
    max_imagens: int = field(default_factory=lambda: _inteiro_positivo("MAX_IMAGES", 20))
    max_bytes_imagem: int = 8 * 1024 * 1024
    cache_notion_segundos: int = field(default_factory=lambda: int(_env("CACHE_NOTION_SEGUNDOS", "60")))
    max_notas: int = 20

    # --- protecao da API (middlewares) ---
    # nomes de host aceitos no cabecalho Host (a Cloudflare repassa o dominio da API)
    hosts_permitidos: tuple[str, ...] = ()
    # quem pode informar o IP real via CF-Connecting-IP (vazio = loopback e redes privadas)
    proxies_confiaveis: str = field(default_factory=lambda: _env("TRUSTED_PROXIES"))
    # o app so manda JSON pequeno; fotos vao pelo WebSocket (limite proprio do servidor)
    max_corpo_bytes: int = field(default_factory=lambda: int(_env("MAX_BODY_BYTES", str(64 * 1024))))
    limite_ip_por_minuto: int = field(default_factory=lambda: int(_env("RATE_LIMIT_IP_PER_MINUTE", "120")))
    limite_usuario_por_minuto: int = field(default_factory=lambda: int(_env("RATE_LIMIT_USER_PER_MINUTE", "90")))
    # tokens invalidos por IP em 5 min antes de bloquear o IP
    max_falhas_auth: int = field(default_factory=lambda: int(_env("MAX_AUTH_FAILURES", "20")))
    max_processamentos_simultaneos: int = field(default_factory=lambda: int(_env("MAX_CONCURRENT_PROCESSING", "6")))
    # tempo maximo esperando a mensagem do app no WebSocket
    ws_espera_segundos: float = 60.0

    def __post_init__(self):
        # valores derivados do dominio (frozen: usa object.__setattr__); passar um valor explicito (testes) continua valendo
        if not self.notion_redirect_uri:
            object.__setattr__(self, "notion_redirect_uri", f"https://{self.dominio}/notion/callback")
        if not self.hosts_permitidos:
            object.__setattr__(self, "hosts_permitidos", (self.dominio, "localhost", "127.0.0.1", "[::1]"))

    @property
    def notion_configurado(self) -> bool:
        return bool(self.notion_client_id and self.notion_client_secret and self.segredo_state)

    def faltando_para_producao(self) -> list[str]:
        obrigatorias = {
            "NOTION_CLIENT_ID": self.notion_client_id,
            "NOTION_CLIENT_SECRET": self.notion_client_secret,
            "ENCRYPTION_KEY": self.chave_criptografia,
            "STATE_SECRET": self.segredo_state,
            "JWT_SECRET": self.jwt_secret,
            "SUPABASE_SERVICE_KEY": self.supabase_chave_servico,
            "OPENAI_API_KEY": self.openai_chave,
        }
        return [nome for nome, valor in obrigatorias.items() if not valor]

    # segredos de assinatura curtos se quebram por forca bruta; so avisa (nao derruba um servidor que ja funciona)
    def segredos_fracos(self) -> list[str]:
        segredos = {"JWT_SECRET": self.jwt_secret, "STATE_SECRET": self.segredo_state}
        return [nome for nome, valor in segredos.items() if valor and len(valor) < 32]
