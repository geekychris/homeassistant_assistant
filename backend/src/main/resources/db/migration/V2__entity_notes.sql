-- Surface useful HA attributes as queryable columns rather than JSON-only.
-- friendly_name beats the entity_registry.name (often null); device_class
-- and unit help the assistant reason about what an entity actually is.
ALTER TABLE ha_entity ADD COLUMN friendly_name VARCHAR(255);
ALTER TABLE ha_entity ADD COLUMN device_class VARCHAR(64);
ALTER TABLE ha_entity ADD COLUMN unit         VARCHAR(32);

CREATE INDEX idx_entity_device_class ON ha_entity(device_class);

-- User/assistant-provided knowledge that must survive HA re-syncs.
-- ha_entity rows can be overwritten by a registry refresh; entity_note rows
-- are kept independently and joined on entity_id.
CREATE TABLE entity_note (
    entity_id   VARCHAR(255) PRIMARY KEY,
    label       VARCHAR(255),       -- friendly nickname ("the LED strip behind the TV")
    note        CLOB,               -- free-form description / behaviour / wiring
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
