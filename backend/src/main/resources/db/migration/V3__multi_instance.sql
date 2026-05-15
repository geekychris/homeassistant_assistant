-- Multi-instance support. Each HA install (e.g. "belmont", "orlando") is one
-- ha_instance row with internal_url + external_url + token. All cached HA
-- data (areas, entities) and user-curated knowledge (notes, aliases) gets
-- scoped by instance_id so the same entity_id can exist on more than one HA.

CREATE TABLE ha_instance (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL UNIQUE,
    internal_url  VARCHAR(255),
    external_url  VARCHAR(255),
    token         CLOB         NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Bootstrap a placeholder default row. The Java startup hook
-- (DefaultInstanceBootstrap) fills in URL + token from env on first boot
-- so existing single-instance setups keep working without manual config.
INSERT INTO ha_instance (id, name, token, is_default)
VALUES (1, 'default', '', TRUE);

-- Add instance_id to every table that holds per-instance data.
ALTER TABLE ha_entity     ADD COLUMN instance_id BIGINT;
ALTER TABLE area          ADD COLUMN instance_id BIGINT;
ALTER TABLE entity_note   ADD COLUMN instance_id BIGINT;
ALTER TABLE entity_alias  ADD COLUMN instance_id BIGINT;
ALTER TABLE schedule      ADD COLUMN instance_id BIGINT;
ALTER TABLE grouping      ADD COLUMN instance_id BIGINT;

-- Backfill existing rows to point at the default instance.
UPDATE ha_entity     SET instance_id = 1 WHERE instance_id IS NULL;
UPDATE area          SET instance_id = 1 WHERE instance_id IS NULL;
UPDATE entity_note   SET instance_id = 1 WHERE instance_id IS NULL;
UPDATE entity_alias  SET instance_id = 1 WHERE instance_id IS NULL;
UPDATE schedule      SET instance_id = 1 WHERE instance_id IS NULL;
UPDATE grouping      SET instance_id = 1 WHERE instance_id IS NULL;

ALTER TABLE ha_entity     ALTER COLUMN instance_id SET NOT NULL;
ALTER TABLE area          ALTER COLUMN instance_id SET NOT NULL;
ALTER TABLE entity_note   ALTER COLUMN instance_id SET NOT NULL;
ALTER TABLE entity_alias  ALTER COLUMN instance_id SET NOT NULL;
ALTER TABLE schedule      ALTER COLUMN instance_id SET NOT NULL;
ALTER TABLE grouping      ALTER COLUMN instance_id SET NOT NULL;

-- Composite PKs so the same HA-side id can appear on >1 instance.
ALTER TABLE ha_entity     DROP PRIMARY KEY;
ALTER TABLE ha_entity     ADD  PRIMARY KEY (instance_id, entity_id);

ALTER TABLE area          DROP PRIMARY KEY;
ALTER TABLE area          ADD  PRIMARY KEY (instance_id, id);

ALTER TABLE entity_note   DROP PRIMARY KEY;
ALTER TABLE entity_note   ADD  PRIMARY KEY (instance_id, entity_id);

CREATE INDEX idx_entity_area_v3      ON ha_entity(instance_id, area_id);
CREATE INDEX idx_entity_domain_v3    ON ha_entity(instance_id, domain);
CREATE INDEX idx_alias_lookup_v3     ON entity_alias(instance_id, alias);
CREATE INDEX idx_schedule_instance   ON schedule(instance_id);
CREATE INDEX idx_grouping_instance   ON grouping(instance_id);
