-- V3 manually inserted ha_instance id=1 for the bootstrap default row but
-- didn't advance the auto-increment sequence, so the next save() tries id=1
-- again and 23505s. Restart at 1000 to clearly separate manual ids from
-- auto-generated ones.
ALTER TABLE ha_instance ALTER COLUMN id RESTART WITH 1000;
