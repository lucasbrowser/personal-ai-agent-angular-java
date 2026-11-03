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

```powershell
cd backend
mvn clean verify
mvn spring-boot:run
```

Endpoints:
- `GET http://localhost:8080/api/v1/health`
- `GET http://localhost:8080/actuator/health`

## 3. Gerar frontend Angular

Execute em outro terminal PowerShell, na raiz:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\init-frontend.ps1
```

Depois:

```powershell
cd frontend
npm start
```

A aplicação ficará disponível em `http://localhost:4200`.

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

Autenticação, integração OpenAI, WebRTC, histórico e memória serão implementados nas etapas seguintes.
