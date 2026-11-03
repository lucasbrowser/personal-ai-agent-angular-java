# Arquitetura — Jarvis

## Decisão arquitetural

Monólito modular no backend, frontend independente e infraestrutura local via Docker Compose.

## Limites da T-01

A T-01 entrega apenas a fundação técnica. O backend não possui ainda autenticação, integração com modelos de IA ou endpoints de negócio.

## Direção das próximas etapas

- T-02: autenticação e usuário único.
- T-03: credenciais efêmeras e sessão OpenAI Realtime.
- T-04: interface de voz e WebRTC.
- T-05: histórico persistente.
- T-06: ferramentas e orquestração.
- T-07: memória.
- T-08: hardening e deploy.

## Segurança

Segredos ficam em variáveis de ambiente. O arquivo `.env` não deve ser versionado. A chave principal da OpenAI nunca será enviada ao navegador.
