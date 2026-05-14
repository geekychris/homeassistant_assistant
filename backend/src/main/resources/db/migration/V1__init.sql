-- Mirrors of HA registries
CREATE TABLE area (
    id           VARCHAR(64) PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    last_synced  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- area_id is intentionally NOT a FK: HA can return entities with orphan area
-- references (e.g. an area the user deleted), and we want to mirror what HA
-- reports, not enforce a stricter invariant than HA itself does.
CREATE TABLE ha_entity (
    entity_id    VARCHAR(255) PRIMARY KEY,
    domain       VARCHAR(64)  NOT NULL,
    name         VARCHAR(255),
    area_id      VARCHAR(64),
    device_id    VARCHAR(64),
    platform     VARCHAR(64),
    state        VARCHAR(255),
    attributes   CLOB,
    last_changed TIMESTAMP,
    last_synced  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_entity_area   ON ha_entity(area_id);
CREATE INDEX idx_entity_domain ON ha_entity(domain);

-- User-defined groupings ("Pool mode", "Movie night")
CREATE TABLE grouping (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(1024),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE grouping_action (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    grouping_id   BIGINT NOT NULL,
    entity_id     VARCHAR(255) NOT NULL,
    domain        VARCHAR(64)  NOT NULL,
    service       VARCHAR(64)  NOT NULL,
    service_data  CLOB,
    sort_order    INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_action_grouping FOREIGN KEY (grouping_id) REFERENCES grouping(id) ON DELETE CASCADE
);

-- Schedules (cron + action payload)
CREATE TABLE schedule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    cron_expr    VARCHAR(128) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    domain       VARCHAR(64)  NOT NULL,
    service      VARCHAR(64)  NOT NULL,
    entity_ids   CLOB,
    service_data CLOB,
    last_run_at  TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Conversation sessions + chat history
CREATE TABLE conversation_session (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT NOT NULL,
    role        VARCHAR(16) NOT NULL,           -- user | assistant | tool | system
    content     CLOB,
    tool_name   VARCHAR(128),
    tool_calls  CLOB,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES conversation_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_msg_session ON chat_message(session_id);

-- Learned NL-to-entity associations
CREATE TABLE entity_alias (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    alias      VARCHAR(255) NOT NULL,
    entity_id  VARCHAR(255) NOT NULL,
    source     VARCHAR(32)  NOT NULL DEFAULT 'user',  -- user | learned
    CONSTRAINT uq_alias UNIQUE (alias, entity_id)
);

CREATE INDEX idx_alias_lookup ON entity_alias(alias);
