-- Every HA service call we make is logged here so the assistant can offer
-- "undo" — "wait, turn that back on" — without the user remembering exactly
-- what changed. Captures the prev_state we read just before the call so a
-- future revision can restore brightness/temperature etc. v1 only inverts
-- turn_on / turn_off / toggle, which covers most accidental toggles.

CREATE TABLE action_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id  BIGINT       NOT NULL,
    entity_id    VARCHAR(255) NOT NULL,
    domain       VARCHAR(64)  NOT NULL,
    service      VARCHAR(64)  NOT NULL,
    service_data CLOB,
    prev_state   VARCHAR(255),
    called_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    undone_at    TIMESTAMP,
    source       VARCHAR(64)  NOT NULL DEFAULT 'unknown'
);

CREATE INDEX idx_action_log_recent ON action_log(instance_id, called_at);
CREATE INDEX idx_action_log_undone ON action_log(undone_at);
