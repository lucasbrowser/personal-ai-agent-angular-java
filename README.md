# Jarvis — Personal AI Agent

Assistente pessoal por voz, acessível pelo navegador.

## Stack

- Java 21
- Spring Boot 4.1.2
- Maven
- Angular 22 (gerado pelo Angular CLI)
- PostgreSQL 17
- Flyway
- Docker Compose

## Pré-requisitos

- JDK 21
- Maven 3.9+
- Node.js 22.22.3+ (Node 26 também é compatível com Angular 22)
- npm
- Docker Desktop com Docker Compose

## 1. Preparar ambiente

No PowerShell, na raiz do repositório:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infrastructure/docker-compose.yml up -d
docker compose -f infrastructure/docker-compose.yml ps
```

Aguarde o PostgreSQL ficar `healthy`.

## 2. Executar backend

Configure `JARVIS_JWT_SECRET` (mínimo de 32 bytes), `JARVIS_ADMIN_EMAIL`,
`JARVIS_ADMIN_PASSWORD` (mínimo de 12 caracteres), `JARVIS_FRONTEND_ORIGIN` e
`JARVIS_SECURE_COOKIE` no ambiente do processo. O `.env.example` contém apenas
valores ilustrativos; substitua-os localmente e nunca reutilize-os em produção.
O inicializador cria o administrador apenas se o e-mail ainda não existir.

```powershell
cd backend
# Na raiz, configure as variáveis do .env no processo atual antes de iniciar:
Get-Content ..\.env | Where-Object { $_ -match '^[A-Z_][A-Z0-9_]*=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
mvn clean verify
mvn spring-boot:run
```

Endpoints:
- `GET http://localhost:8080/api/v1/health`
- `GET http://localhost:8080/actuator/health`

## 3. Gerar frontend Angular

Execute em outro terminal PowerShell, na raiz:

```powershell
cd frontend
npm ci
npm start
```

Se o scaffold do frontend ainda não foi gerado, execute primeiro o script abaixo na raiz:

```powershell
cd ..
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\init-frontend.ps1
```

A aplicação ficará disponível em `http://localhost:4200`.
O proxy de desenvolvimento encaminha `/api` para `http://localhost:8080`,
permitindo o uso do cookie CSRF pelo cliente Angular. Em produção, publique
frontend e API sob a mesma origem (ou configure `JARVIS_FRONTEND_ORIGIN` com a
origem exata do frontend). Ative `JARVIS_SECURE_COOKIE=true` com HTTPS.

## Autenticação (T-02)

- `POST /api/v1/auth/login`: recebe `{ "email", "password" }`, retorna access
  token de 15 minutos e define refresh cookie `HttpOnly; SameSite=Strict`.
- `POST /api/v1/auth/refresh`: rotaciona o refresh cookie (validade de 7 dias).
- `POST /api/v1/auth/logout`: revoga o refresh token e remove o cookie.
- `GET /api/v1/auth/me`: retorna o usuário autenticado.
- `GET /api/v1/auth/csrf`: emite o cookie/token CSRF utilizado por operações
  de autenticação baseadas em cookie.
- `GET /api/v1/health` permanece público.

O access token é mantido somente em memória no Angular. Os refresh tokens são
opacos e persistidos somente como SHA-256. Login, refresh e logout validam a
origem; refresh e logout também exigem CSRF. Não há cadastro público.

## Voz em tempo real (T-03)

O backend cria credenciais efêmeras em `POST /api/v1/voice/sessions`, protegido
pela autenticação JWT existente. A identidade do usuário vem exclusivamente
do principal autenticado. A resposta usa `Cache-Control: no-store`; o limite em
memória é de cinco credenciais por usuário por minuto por padrão. A chave
principal da OpenAI é usada somente pelo backend.

Configure estas variáveis no processo do backend (`.env.example` contém apenas
valores ilustrativos):

| Variável | Padrão | Uso |
| --- | --- | --- |
| `OPENAI_API_KEY` | obrigatória | Chave usada somente pelo backend; sem ela, o backend não inicia |
| `OPENAI_REALTIME_MODEL` | `gpt-realtime-2.1-mini` | Modelo Realtime |
| `OPENAI_REALTIME_VOICE` | `marin` | Voz de saída |
| `OPENAI_REALTIME_CLIENT_SECRET_TTL_SECONDS` | `600` | Validade da credencial efêmera, de 10 a 7200 segundos |
| `OPENAI_REALTIME_CONNECT_TIMEOUT` | `3s` | Timeout de conexão com o provedor |
| `OPENAI_REALTIME_RESPONSE_TIMEOUT` | `10s` | Timeout da resposta do provedor |
| `JARVIS_VOICE_SESSIONS_PER_MINUTE` | `5` | Limite por usuário, de 1 a 60 |

Copie `.env.example` para `.env`, configure `OPENAI_API_KEY` e carregue as
variáveis no processo do backend conforme as instruções PowerShell acima. Nunca
versione uma chave real nem a inclua no ambiente do frontend.

Depois de iniciar backend e Angular, faça login e selecione **Iniciar conversa**.
O navegador solicitará acesso ao microfone (use `localhost` ou HTTPS), receberá
uma credencial efêmera do Jarvis e conectará diretamente ao endpoint GA
`POST https://api.openai.com/v1/realtime/calls` com essa credencial temporária.
O `server_vad` detecta a fala e interrompe automaticamente uma resposta quando
o usuário começa a falar. **Encerrar conversa** para as tracks do microfone,
fecha o DataChannel e a conexão peer, remove listeners e libera o elemento de
áudio remoto. As transcrições aparecem quando o servidor Realtime as envia; as
conversas não são persistidas.

Negação de permissão, credencial expirada, falhas de conexão e indisponibilidade
ou limite do provedor aparecem na interface. Inicie outra sessão para tentar
novamente. O limite local reduz pedidos repetidos de credenciais; não é um
orçamento financeiro e não substitui os alertas de consumo do provedor. O custo
Realtime depende do uso de áudio e texto. Consulte os [preços atuais da
OpenAI](https://developers.openai.com/api/docs/pricing) e o [guia de custos
Realtime](https://developers.openai.com/api/docs/guides/realtime#cost).

A implementação segue a [Realtime API GA](https://developers.openai.com/api/docs/guides/realtime),
o [guia WebRTC](https://developers.openai.com/api/docs/guides/voice-webrtc) e a
[referência de client secrets](https://developers.openai.com/api/reference/resources/realtime/subresources/client_secrets/methods/create).

## 4. Encerrar infraestrutura

Na raiz:

```powershell
docker compose --env-file .env -f infrastructure/docker-compose.yml down
```

Para apagar também os dados locais do banco (operação destrutiva):

```powershell
docker compose -f infrastructure/docker-compose.yml down -v
```

## Escopo da T-01

- Estrutura inicial do repositório.
- Backend Spring Boot.
- Endpoint básico de saúde.
- PostgreSQL via Docker Compose.
- Migração inicial Flyway.
- Teste unitário inicial.
- Script de geração do frontend Angular.

Autenticação foi implementada na T-02. Integração OpenAI, WebRTC, histórico e memória continuam nas etapas seguintes.
