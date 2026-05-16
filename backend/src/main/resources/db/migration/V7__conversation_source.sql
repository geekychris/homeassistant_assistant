-- Tag conversations with where they came from so non-web entry points
-- (CLI, Telegram, …) can find or create their own per-channel session
-- without colliding with the user's web sessions.
--
-- For Telegram we map one chat → one session, keyed by (source='telegram',
-- external_id=<chat_id as string>). NULL external_id is fine for sessions
-- created from the web — they don't need to be looked up by anything.

ALTER TABLE conversation_session ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'web';
ALTER TABLE conversation_session ADD COLUMN external_id VARCHAR(128);

CREATE UNIQUE INDEX idx_session_source_external ON conversation_session(source, external_id);
