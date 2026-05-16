# homeassistant_assistant

A conversational layer on top of [Home Assistant](https://www.home-assistant.io/).
HA runs the house; this app is the friendlier interface — local LLM
chat with native tool calls, a grouped tap UI, schedules, persistent
device learning, and multi-instance support.

See [CLAUDE.md](./CLAUDE.md) for architecture and conventions.

## Stack

- **Backend**: Java 21, Spring Boot 3.4, H2 (file mode) + Flyway, Maven
- **LLM**: Ollama running locally, native tool calling
- **Frontend**: Vite + React + TypeScript (PWA)
- **CLI**: single-file Python script in `cli/hass`
- **Telegram bridge**: optional, long-poll, allowlisted chats only

## Run it

Secrets live in `local.env` at the repo root (gitignored). See `CLAUDE.md`
for the variables.

```bash
cd backend
set -a && source ../local.env && set +a
mvn spring-boot:run
```

```bash
cd frontend
npm install && npm run dev
```

```bash
cli/hass "turn off the kitchen chandelier"
```
