# NekoNote — app Android

App nativo (Kotlin + Jetpack Compose) que fotografa páginas, extrai o texto no servidor e cria uma nota no Notion do usuário.

## Como rodar

Requisitos: Android Studio (ou JDK 17+ e Android SDK 37) e um celular com depuração USB.

```bash
cd mobile
./gradlew :app:assembleDebug        # gera app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # instala no celular conectado por USB
```

Copie `local.properties.example` para `local.properties` e ajuste o `sdk.dir`.

### Configuração

Preencha em `local.properties`:

```properties
nekonote.dominio=api.korvon.com.br   # o mesmo API_DOMAIN do api/.env
google.idClienteWeb=<ID do cliente OAuth Web do Google Cloud>
```

O login é **somente com o Google**, feito pelo **Supabase Auth** (que roda no Docker da `api/`): o app pega o ID token do Google
(Credential Manager), a API repassa a troca por uma sessão do Supabase em `/auth/v1/token` e o app passa a mandar o token de acesso nas chamadas.
No Google Cloud, o cliente *Android* (pacote `br.com.korvon.nekonote` + SHA-1; `./gradlew :app:signingReport` mostra o do build de debug)
e o cliente *Web* precisam existir, e o ID do cliente Web é o mesmo configurado em `GOOGLE_CLIENT_ID` no `api/.env`.

## Estrutura (`app/src/main/java/br/com/korvon/nekonote`)

| Pasta | Conteúdo |
|---|---|
| `dados/` | modelos, configuração, preferências locais |
| `dados/auth/` | login com Google (Credential Manager + Supabase Auth) |
| `dados/api/` | cliente REST da API NekoNote |
| `dados/fotos/` | sessão de fotos (máx. 20), normalização de imagem, prévia de legibilidade (ML Kit) |
| `dados/processamento/` | cliente WebSocket `/ws/ocr` (progresso, reconexão 1/2/4 s) |
| `visual/telas/` | abertura, login (Google), conexão com o Notion (2 passos), captura, seleção, configurar, processando e configurações |
| `visual/componentes/`, `visual/tema/` | componentes e tema (laranja, grafite, roxo, fonte Maven Pro) |

## Contrato da API (implementado em `api/`)

Todas as rotas recebem `Authorization: Bearer <token de acesso do Supabase Auth>`.

| Rota | Resposta |
|---|---|
| `GET /conta` | `{"email", "id_publico", "notion": {"status": "conectado"\|"desconectado"\|"reconectar", "espaco_nome", "espaco_icone", "paginas": 4}}` |
| `POST /notion/conectar` | `{"url_autorizacao": "..."}` (o app abre em Custom Tab; a página de retorno abre `nekonote://notion?status=ok\|cancelado\|erro`) |
| `GET /notion/paginas` | `{"paginas": [{"id", "titulo", "icone"}]}` |
| `DELETE /notion/conexao` | 2xx |
| `DELETE /conta` | 2xx |
| `WS /ws/ocr` | header `Authorization`; mensagem `{"acao":"processar","id_token","titulo","id_pagina","modalidades":{"topicos","resumo","assunto"},"imagens":[base64]}`; eventos `inicio`, `progresso`, `enviando`, `resultado` (com `url_pagina`), `erro` |

Notas: o app nunca envia nem recebe o token do Notion. As imagens saem com lado maior ≤ 2000 px (JPEG 85); o total em base64 é limitado a ~12 MB por causa do limite de 16 MiB por mensagem do uvicorn.

## Pendências conhecidas

- O app só cria páginas: a lista de notas e a gravação de áudio foram removidas do app (a rota `GET /notas` continua na API, sem uso no app).
- O servidor (`api/`) já implementa o contrato acima; veja `api/README.md` para configurar o login e o Notion.
- O APK de debug usa a chave de debug; para distribuir, crie uma chave própria.
