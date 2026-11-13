# NekoNote — API (FastAPI)

Servidor do NekoNote: valida o login (Supabase Auth com Google), conecta o Notion de cada usuário (OAuth), faz o OCR das fotos (Tesseract),
gera o resumo, os tópicos e os assuntos com a OpenAI, cria a página no Notion do próprio usuário e entrega o progresso ao app pelo WebSocket.

```
App ──token de login (Supabase Auth + Google)──▶ API ──┬─▶ Supabase (Postgres, no Docker: só tokens OAuth do Notion, cifrados)
                                 ├─▶ Notion API (páginas liberadas, notas)
                                 ├─▶ OpenAI (resumo/tópicos/assuntos a partir do texto das fotos)
                                 └─▶ Notion API (cria a subpágina com o token do próprio usuário)
```

## Rodar

```bash
cd API
python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt   # Windows (Linux/Mac: .venv/bin/pip)
cp .env.example .env                                                        # preencha (veja "Configurar")
.venv/Scripts/python main.py                                                # http://localhost:8000
.venv/Scripts/python -m pytest                                              # 126 testes (nada sai da máquina)
```

Em produção: `docker compose up -d --build` (FastAPI + Supabase + túnel Cloudflare). A imagem já traz o Tesseract com o idioma `por`.
Sem as variáveis obrigatórias o servidor sobe mesmo assim: `/`, `/privacidade`, `/termos` e `/saude` funcionam e as demais rotas respondem 503
(útil para registrar a integração no Notion, que exige essas páginas no ar).

## Configurar (passo a passo)

0. **Domínio** — em `api/.env` defina `API_DOMAIN` (subdomínio + domínio, ex.: `api.korvon.com.br`). É a **única** configuração de endereço: dela saem o redirect do Notion,
   a URL do login (`/auth/v1`), os hosts aceitos e o `GOTRUE_SITE_URL`. No app Android use o mesmo valor em `mobile/local.properties` (`nekonote.dominio`).
1. **Supabase e login com Google** — sobem junto no `docker compose` (banco + Auth/GoTrue só com Google + PostgREST; para olhar o banco use um cliente SQL em `127.0.0.1:54322`).
   Gere `POSTGRES_PASSWORD`, `JWT_SECRET` e as chaves `SUPABASE_*` com `python supabase/gerar_chaves.py`; a tabela `usuarios` é criada sozinha na primeira subida.
   Em `.env` defina `GOOGLE_CLIENT_ID` (ID do cliente OAuth *Web* do Google Cloud, o mesmo do app) com a redirect URI `https://<API_DOMAIN>/auth/v1/callback`.
   O app chega ao GoTrue por `https://<API_DOMAIN>/auth/v1/...`: a própria API repassa só `token`, `user`, `logout`, `settings` e `health` (as rotas de administração nunca saem pela internet).
   No Google Cloud, o cliente Android (pacote `br.com.korvon.nekonote` + SHA-1) continua necessário.
2. **Notion** — em notion.so/profile/integrations crie uma integração **pública** (empresa Korvon), redirect URI
   `https://<API_DOMAIN>/notion/callback`, política de privacidade `https://<API_DOMAIN>/privacidade`, termos `.../termos`.
   Capacidades: ler e inserir conteúdo. Copie `client_id` e `client_secret` para o `.env`.
3. **Segredos** — gere `ENCRYPTION_KEY` e `STATE_SECRET` com os comandos do `.env.example`.
4. **OpenAI** — crie uma chave em platform.openai.com e coloque em `OPENAI_API_KEY` (obrigatória). O modelo padrão é `gpt-4.1-mini` (`OPENAI_MODEL`). Defina um limite de gasto mensal no painel da OpenAI.
5. **App** — preencha `nekonote.dominio` e `google.idClienteWeb` em `mobile/local.properties` (ver `mobile/README.md`).

O `.env.example` tem só o essencial; os ajustes finos de proteção (limites por minuto, tamanho de corpo etc.) têm padrão em `src/configuracao.py` e podem ser definidos como variáveis de ambiente se necessário.

## Endpoints

Todas exigem `Authorization: Bearer <token de acesso do Supabase Auth>`, exceto as marcadas como públicas. A API confere a assinatura (JWT HS256 com o `JWT_SECRET`).

| Método | Rota | Função |
|---|---|---|
| GET | `/conta` | e-mail, `id_publico` e bloco `notion` (`status`: conectado/desconectado/reconectar, `espaco_nome`, `espaco_icone`, `paginas`). Nunca devolve tokens |
| DELETE | `/conta` | apaga os tokens do Notion e a conta de login no Supabase Auth |
| POST | `/notion/conectar` | `{url_autorizacao}` com `state` assinado (HMAC, 10 min, sem estado no servidor) |
| GET | `/notion/callback` | **pública**: troca o `code`, grava os tokens cifrados e abre `nekonote://notion?status=ok\|cancelado\|erro` |
| GET | `/notion/paginas` | páginas liberadas (sem as notas 🐾), cache de 60 s |
| DELETE | `/notion/conexao` | apaga os tokens (desconectar) |
| GET | `/notas` | cards da Home (subpáginas 🐾), mais recentes primeiro, até 20 |
| GET | `/`, `/privacidade`, `/termos`, `/saude` | **públicas** |
| WS | `/ws/ocr` | processamento (abaixo) |

### Erros (formato único)

Todo erro, de qualquer rota ou middleware, tem o mesmo corpo. `mensagem` é para o usuário; `codigo` é estável e serve para o app decidir o que fazer:

```json
{"codigo": "LIMITE_EXCEDIDO", "mensagem": "Muitas requisições. Tente de novo em instantes.", "request_id": "9f1c…"}
```

| HTTP | `codigo` | Quando |
|---|---|---|
| 400 | `REQUISICAO_INVALIDA`, `LOGIN_NAO_SUPORTADO` | cabeçalho/corpo malformado; tipo de login fora de `id_token`/`refresh_token` |
| 401 | `NAO_AUTENTICADO`, `SESSAO_INVALIDA` | sem token, ou token inválido/expirado (o app renova e repete) |
| 404 / 405 | `NAO_ENCONTRADO`, `METODO_NAO_PERMITIDO` | rota ou método inexistente (só para quem está logado: sem login é sempre 401) |
| 409 | `NOTION_RECONECTAR`, `NOTION_DESCONECTADO` | acompanha `"status": "reconectar" \| "desconectado"` (campo que o app já usa) |
| 413 | `CORPO_GRANDE_DEMAIS` | corpo HTTP acima de `MAX_BODY_BYTES` (64 KB) |
| 422 | `VALIDACAO` | `campos`: onde falhou, sem ecoar o valor enviado |
| 429 | `LIMITE_EXCEDIDO` | cabeçalho `Retry-After` (segundos) e `tentar_em_segundos` no corpo |
| 500 | `ERRO_INTERNO` | nunca traz detalhe interno; use o `request_id` para achar no log |
| 502 / 503 | `NOTION_INDISPONIVEL`, `NOTION_LIMITANDO`, `LOGIN_INDISPONIVEL`, `NAO_CONFIGURADO` | dependência fora do ar |

Toda resposta traz `X-Request-ID` (aceita o do cliente se tiver 8–64 caracteres `[A-Za-z0-9_-]`, senão gera um). Respostas autenticadas trazem `RateLimit-Limit` e `RateLimit-Remaining`.

Compatibilidade: rotas, métodos e campos de sucesso não mudaram. Mudanças futuras devem ser aditivas (campo novo opcional); remover ou mudar tipo de campo exige nova versão da API.

### WebSocket `/ws/ocr`

Cabeçalho `Authorization: Bearer <ID token>` **obrigatório** no handshake (ausente ou inválido → o handshake é recusado com 401, a conexão nem abre) e `id_token` opcional em `processar` (validado de novo e tem de ser do mesmo usuário).
A conexão fecha se o pedido não chegar em 60 s ou depois de 20 mensagens sem pedido. Um processamento por usuário por vez (`MAX_CONCURRENT_PROCESSING` no servidor todo).

```json
{"acao": "processar", "id_token": "...", "id_pagina": "<id Notion>",
 "modalidades": {"topicos": true, "resumo": true, "assunto": false, "apresentacao": false},
 "temperamento": "equilibrado", "imagens": ["<base64>", "..."]}
```

Eventos: `inicio` → `progresso` (`percentual` 0–90, `imagem_atual`, `total`) → `enviando` ("Gerando resumos…": a IA escreve) → `criando` ("Criando a página no Notion…") → `resultado` (`mensagem` e `url_pagina`) ou `erro` (`mensagem`).

`id_pedido` (8–64 caracteres `[A-Za-z0-9_-]`, escolhido pelo app) identifica o envio e é o mesmo em todas as tentativas dele. O processamento roda como tarefa própria, desligada da conexão: se o WebSocket cair e o app reconectar com o mesmo `id_pedido`, ele se encaixa na tarefa em andamento (ou recebe o resultado guardado por 1 h) em vez de gastar IA e criar a página de novo. Mesmo `id_pedido` com conteúdo diferente é recusado; sem ninguém acompanhando por 30 s a tarefa é cancelada. Sem `id_pedido` (app antigo) cada envio vale por si.

Validações: ≥ 1 modalidade (tópicos, resumo, assunto ou apresentação), `temperamento` opcional (equilibrado, direto, divertido, tecnico ou didatico; outro valor vira equilibrado), 1–20 imagens (≤ 8 MB cada), página de destino acessível, Notion conectado,
limite de 10 processamentos/hora por usuário (conta por pedido, não por reconexão). O token do Notion **nunca** vem do app: a API o lê do Supabase e renova se preciso.

## Como a nota é gerada

1. O usuário autoriza o Notion no app; a API guarda o `access_token` e o `refresh_token` **cifrados** (Fernet) na tabela `usuarios` do Supabase.
2. Ao processar, a API confirma o token no Notion (renovando o par inteiro, sob trava por usuário, se ele expirou), faz o OCR das fotos e manda **todos os textos de uma vez** à OpenAI
   (`src/ia/`): prompt em `prompt.py`, chamada com *structured outputs* (esquema estrito em `esquema.py`: `resumo`, `assuntos[]`, `topicos[]`, `apresentacao[]`), 2 tentativas em 429/5xx/timeout.
   O texto das fotos é tratado como dado (instrução dentro dele é ignorada) e a resposta da IA é validada antes de uso. Nem o texto nem a resposta vão para o log.
3. `src/notion/blocos.py` converte a nota em blocos do Notion (trechos de até 2000 letras, lotes de 100 blocos) e `ServicoNotion.criar_nota` cria a página com o token do próprio usuário
   (`POST /v1/pages`, renovando o token se vencer) e anexa os lotes restantes devagar (limite do Notion: ~3 pedidos/s).
4. O **título** e o **emoji** da página também são escolhidos pela IA (campos `titulo` e `emoji` da resposta; se o emoji vier inválido, usa 🐾, e sem título, "Nova nota"). Como o emoji varia, as notas do app (`/notas`) são reconhecidas como as páginas **criadas pela integração** (`created_by` = usuário-bot de `/v1/users/me`). A página tem as seções `Resumo`, `Assuntos`, `Tópicos`, `Apresentação` (só as ligadas): é nesse formato que `/notas` monta os cards. Se mudar esses títulos, ajuste `src/notion/leitura.py`.
   O `temperamento` (equilibrado, direto, divertido, técnico, didático) muda só o tom da escrita e fica no topo de `src/ia/prompt.py`.

## Segurança

### Cadeia de middlewares (`src/protecao/middlewares.py`)

Toda requisição, HTTP ou WebSocket, passa por estas camadas antes de chegar a uma rota (de fora para dentro):

| Camada | O que faz |
|---|---|
| `Requisicao` | `X-Request-ID`, log de acesso (método, caminho, status, tempo, IP, usuário; **sem query string** nem cabeçalhos) e rede de segurança: exceção inesperada vira 500 genérico |
| `CabecalhosDeSeguranca` | `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, HSTS, CSP restritiva, `Permissions-Policy`, CORP |
| `HostConfiavel` | Host diferente de `API_DOMAIN` (ou localhost) → 400 |
| `LimiteDeCorpo` | corpo HTTP > `MAX_BODY_BYTES` → 413 (pelo `Content-Length` e contando bytes em *chunked*) |
| `Autenticacao` | **negado por padrão**: só passa sem token o que está em `protecao/rotas_publicas.py`; o resto exige JWT válido (HS256 fixo, `aud`/`role` = `authenticated`, assinatura em tempo constante). Inclui rotas que ainda não existem |

CORS fica desligado de propósito: o único cliente é o app Android, então nenhum site consegue chamar a API pelo navegador de um usuário.
`/docs`, `/redoc` e `/openapi.json` não existem. Rota nova nasce protegida; para torná-la pública é preciso editar `rotas_publicas.py` de propósito.

### Limite de taxa (`src/protecao/limites.py`)

Janela deslizante em memória, memória limitada (centenas de milhares de IPs falsos não estouram o servidor). Duas etapas: **por IP** antes de olhar o token (protege login, callback e a própria validação do JWT) e **por usuário** depois.

| Política | Limite |
|---|---|
| IP, qualquer rota (inclui públicas) | 120/min (`RATE_LIMIT_IP_PER_MINUTE`) |
| IP, `/auth/v1/*` (login) | 20/min |
| IP, `/notion/callback` | 20/min |
| IP, `/ws/ocr` (handshake) | 10/min |
| IP com 20 tokens inválidos em 5 min | **bloqueado** (429) até esfriar (`MAX_AUTH_FAILURES`) |
| Usuário, qualquer rota | 90/min (`RATE_LIMIT_USER_PER_MINUTE`) |
| Usuário, `POST /notion/conectar` | 10 / 10 min |
| Usuário, `GET /notion/paginas` e `/notas` (cada um chama o Notion) | 30/min |
| Usuário, `DELETE /conta` | 3/hora |
| Usuário, `/ws/ocr` | 10/min, e 10 processamentos/hora (`PROCESSING_LIMIT_PER_HOUR`) |
| Usuário, `/ws/ocr` (pedido) | até `MAX_IMAGES` imagens por envio (padrão 20) |

O IP real vem de `CF-Connecting-IP`, **só** quando a conexão chega de loopback/rede privada (o túnel). De qualquer outro IP o cabeçalho é ignorado. IPv6 conta por /64. Por isso o `docker-compose.yml` publica a API em `127.0.0.1:8000`: assim ninguém contorna a Cloudflare.
O estado fica em memória de **um** processo (`uvicorn` com 1 worker). Para subir vários workers/réplicas, mova os contadores para o Redis.

### Demais proteções

- Login com Google, sem senhas. A API valida o token de acesso do Supabase em REST e WebSocket; o proxy `/auth/v1` só repassa `token` (apenas `id_token`/`refresh_token`), `user`, `logout`, `settings`, `health`, cada um com seus métodos.
- OCR: um processamento por usuário, teto no servidor, imagem "bomba" (pixels demais) recusada, base64 gigante recusado antes de decodificar, id de página validado.
- WebSocket: espera máxima pelo pedido, limite de mensagens sem pedido, mensagem binária ou lixo não derruba a conexão, nada do que o cliente envia é devolvido nos erros.
- Callback do OAuth: CSP com *nonce* (sem `unsafe-inline`), `state` assinado com HMAC e validade.
- Container: usuário sem privilégio, sistema de arquivos somente leitura, `cap_drop: ALL`, `no-new-privileges`, só as variáveis de ambiente que a API usa, `HEALTHCHECK`.
- `uvicorn`: sem log de acesso próprio (gravaria `code`/`state` do OAuth), sem cabeçalho `Server`, `limit_concurrency`, `timeout_keep_alive` curto.
- Dependências: rode `pip install pip-audit && pip-audit -r requirements.txt` antes de cada deploy.

### Antes de publicar (checklist)

1. Troque `JWT_SECRET`, `STATE_SECRET`, `ENCRYPTION_KEY` por valores aleatórios de 32+ caracteres (a API avisa no log se algum for curto). **Perder a `ENCRYPTION_KEY` invalida os tokens do Notion salvos.**
2. Rotacione o `CLOUDFLARE_TOKEN` se algum dia foi versionado e ative no painel da Cloudflare: *WAF managed rules*, *Bot Fight Mode* e uma regra de *rate limiting* para `api.korvon.com.br` (primeira linha de defesa, antes de chegar na máquina).
3. No servidor, não abra a porta 8000 no firewall: a API só deve ser alcançada pelo túnel.
4. Confirme `docker compose ps`: só `127.0.0.1:8000` e `:54322` publicados.
5. Defina um limite de gasto mensal na OpenAI: cada pedido custa uma chamada, e a API já limita 10 por hora por usuário e 300 mil caracteres por pedido.

### Já existia

- Não há senhas: o login é só com Google.
- Tokens do Notion cifrados em repouso, nunca devolvidos ao app e nunca logados; `state` do OAuth assinado, com expiração.
- Imagens e textos só existem em memória durante o processamento; os logs não têm conteúdo.
- O histórico do Git tem o commit "Delete .env": se o `CLOUDFLARE_TOKEN` já foi versionado, **rotacione-o**.
