# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A conversational layer on top of [Home Assistant](https://www.home-assistant.io/). The user runs HA on their LAN but finds the stock UI clunky for everyday use. This app sits in front of it and offers:

- Natural-language control via a local LLM (Ollama), with multi-step intents
  ("I'm at the pool, turn on the pump and strip lights")
- Clarifying dialogs ("I'm going to bed" → asks whether to leave the bedroom main on)
- Simple user-defined automations (on/off schedules, event triggers) created from chat
- A simplified grouped tap UI as an alternative to the HA dashboard
- Voice in/out — **deferred to v2**; ship text + grouped UI first

## Stack & versions

- **Backend**: Java 21 LTS (what's installed on this machine via Homebrew `openjdk@21`), Spring Boot 3.4.x, Maven 3.9+. Single `backend/` module to start.
- **DB**: H2 in **file mode** (`jdbc:h2:file:./data/haassistant`) via Spring Data JPA + Hibernate. Migrations via Flyway. H2 was chosen over SQLite for first-class Hibernate dialect support; revisit if we outgrow it.
- **LLM**: Ollama running locally at `http://localhost:11434`, model `llama3.3`. Use the `/api/chat` endpoint with native tool-calling.
- **Frontend**: Vite + React + TypeScript. Live state via a WebSocket from the backend.
- **Future**: iOS / Android clients hitting the same backend API. Node-RED integration is deferred — Spring's `@Scheduled` covers the v1 needs.

## Repository layout (planned)

```
homeassistant_assistant/
├── CLAUDE.md
├── local.env                        # gitignored — HA_URL, HA_TOKEN, OLLAMA_*
├── backend/                         # Spring Boot
│   ├── pom.xml
│   └── src/main/java/com/hitorro/haassistant/
│       ├── HaAssistantApplication.java
│       ├── ha/                      # HomeAssistantClient (WebSocket), entity/area sync
│       ├── ollama/                  # OllamaClient, tool schema definitions
│       ├── conversation/            # per-session state, tool dispatch loop
│       ├── schedule/                # @Scheduled trigger executor reading from DB
│       ├── db/                      # JPA entities, Spring Data repositories, Flyway migrations
│       └── api/                     # REST + WebSocket controllers for the UI
│   └── src/main/resources/
│       ├── application.yml          # committed defaults, env-var placeholders
│       ├── application-local.yml    # gitignored — local overrides if needed
│       └── db/migration/            # Flyway SQL: V1__init.sql, etc.
└── frontend/                        # Vite + React + TS
```

## Local config & secrets

Secrets live in **`local.env`** at the repo root (gitignored). It defines:

- `HA_URL` — base URL of the Home Assistant instance
- `HA_TOKEN` — long-lived access token (created in HA → user profile → Security)
- `OLLAMA_URL`, `OLLAMA_MODEL`
- `DB_URL` — JDBC URL for H2 (e.g. `jdbc:h2:file:./data/haassistant`); `DB_USER` / `DB_PASSWORD` if you set them

`application.yml` should reference these as `${HA_URL}`, `${HA_TOKEN}`, etc. — never hard-code. When running locally, `set -a && source local.env && set +a` before `./mvnw spring-boot:run`. Never echo, log, or paste the token into any committed file (including CLAUDE.md, READMEs, or code comments).

## Build & run (once scaffolded)

```bash
# Backend (uses system mvn; run `mvn wrapper:wrapper` once to pin a wrapper if you want)
cd backend
set -a && source ../local.env && set +a   # export env vars first
mvn spring-boot:run                       # dev
mvn test                                  # all tests
mvn test -Dtest=HomeAssistantClientTest   # one class
mvn test -Dtest=HomeAssistantClientTest#connects   # one method
mvn package                               # build jar

# Frontend
cd frontend
npm install
npm run dev          # vite dev server
npm run build
npm run test
```

## Architecture notes (the load-bearing parts)

### Home Assistant integration

HA exposes two APIs; **use the WebSocket** as primary because it streams state changes and exposes the registries we need.

- Endpoint: `ws(s)://<host>/api/websocket`
- **Auth handshake**: server sends `auth_required` → client sends `{"type":"auth","access_token":"..."}` → server sends `auth_ok` (or `auth_invalid`). Only after `auth_ok` may the client send commands.
- Commands use a client-incrementing integer `id` field. Server replies reference the same id.
- **Bootstrap calls** to pull on startup and cache in SQLite:
  - `get_states` — current entity states
  - `config/entity_registry/list` — entity metadata (`area_id`, `device_id`, `platform`)
  - `config/device_registry/list` — devices and their `area_id`
  - `config/area_registry/list` — area names (Pool, Bedroom, etc.)
- **Live updates**: `subscribe_events` with `event_type: state_changed` — apply to the in-memory state cache and push diffs to the UI WS.
- **Acting**: `call_service` (`domain`, `service`, `service_data`, `target`). This is what the LLM tools ultimately invoke.
- Reconnect with backoff on disconnect; re-auth and re-subscribe on reconnect.

### Ollama / conversation loop

- Single endpoint: `POST {OLLAMA_URL}/api/chat` with `model`, `messages`, `tools`, `stream:false` (start non-streaming, add streaming later).
- Tools are JSON schemas. The backend owns the tool registry; on each tool call the LLM returns, dispatch to the matching Java handler, append the tool result as a `role:"tool"` message, and call `/api/chat` again until the model returns a plain assistant message.
- **Initial tool set**:
  - `list_areas()` → `[{id, name}]`
  - `list_entities(area_id?, domain?)` → entities with current state + capabilities
  - `get_entity_state(entity_id)`
  - `call_ha_service(domain, service, entity_ids[], data?)` — the workhorse
  - `create_schedule(cron, entity_ids[], action, data?)` — persists to SQLite; the scheduler picks it up
  - `list_schedules()`, `delete_schedule(id)`
  - `ask_user(question, options?)` — emits a clarifying-question event to the UI; the UI's reply becomes the next user turn. This is how "I'm going to bed" → "turn off all lights but leave bedroom main on?" works.
- Conversation history is per-session and persisted in SQLite so the assistant can resume context. Cap the rolling window to keep prompts cheap.
- Latency on llama3.3 will be seconds per turn on consumer hardware — that's expected. Don't add timeouts so aggressive that tool-heavy turns get cancelled.

### Local state model

H2 (file mode) stores, modelled as JPA entities:
- Mirrors of HA's area/entity/device registries (updated on bootstrap + on registry-change events)
- User-defined **groupings/scenes** (e.g. "Pool mode" → set of entity actions) that are independent of HA's own scenes
- **Schedules** — cron expression + target entities + action payload
- **Conversation sessions** + message history
- **Learned associations** — e.g. "the pool pump" → `switch.pool_pump` mappings so the LLM doesn't have to re-resolve them each turn

Schema is owned by Flyway migrations (`db/migration/V*__*.sql`), not Hibernate `ddl-auto`. Set `spring.jpa.hibernate.ddl-auto=validate` so Hibernate cross-checks entities against the migrated schema at startup.

### Scheduler

Spring `@Scheduled` task running every minute (or whatever granularity you need) reads pending triggers from the `schedules` table and invokes the `call_ha_service` path. Keep this dumb — no LLM in the trigger path. The LLM only writes the rows.

### Frontend → backend contract

- **REST** for CRUD: areas, entities (read-only cache), groupings, schedules, sessions.
- **WebSocket** for: live entity state diffs, chat streaming, `ask_user` clarification events.
- The "simplified UI" is fundamentally area-grouped and capability-grouped views over the same cached entity registry the LLM uses — keep one source of truth.

## Conventions

- Package root: `com.hitorro.haassistant`.
- HA WebSocket id allocation is centralised in `HomeAssistantClient`; never let two call sites generate ids.
- All outbound HA service calls go through one method so logging, rate-limiting, and dry-run mode are uniform.
- Tool handlers return plain Java records that serialise straight to the JSON the LLM sees — keep tool I/O boring and predictable.
- Don't leak HA-internal fields (like `context.parent_id`) to the LLM; project to a clean shape.
