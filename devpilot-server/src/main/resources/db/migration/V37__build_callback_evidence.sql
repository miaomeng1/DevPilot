-- No backfill: historical pipeline rows do not prove delivery with the current key.
ALTER TABLE cicd_configuration ADD COLUMN build_callback_verified_at TIMESTAMP NULL;
