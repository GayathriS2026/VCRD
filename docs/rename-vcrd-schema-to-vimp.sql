-- Run once in the existing PostgreSQL database (solardb in production).
-- Stop every backend instance first. Run as the schema owner or an administrator.
-- The rename preserves tables, data, ownership, grants, and Flyway history.
-- If vimp already exists, this fails instead of merging or replacing schemas.
BEGIN;
ALTER SCHEMA vcrd RENAME TO vimp;
COMMIT;
