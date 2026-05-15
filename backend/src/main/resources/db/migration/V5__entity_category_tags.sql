-- Persistent classification: category overrides the raw HA domain when
-- categorising what an entity actually is (e.g. a Z-Wave dimmer that HA
-- reports as a 'switch' but is really a light). tags is a free-form list
-- of words for searching and chat context (e.g. ["dimmable", "pendant",
-- "kitchen"]). Both are user/assistant-curated and survive HA syncs.
ALTER TABLE entity_note ADD COLUMN category VARCHAR(64);
ALTER TABLE entity_note ADD COLUMN tags     CLOB;

CREATE INDEX idx_note_category ON entity_note(category);
