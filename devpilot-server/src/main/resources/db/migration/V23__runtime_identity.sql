ALTER TABLE docker_container_snapshot ADD COLUMN runtime_key VARCHAR(600);
ALTER TABLE application ADD COLUMN runtime_key VARCHAR(600);
