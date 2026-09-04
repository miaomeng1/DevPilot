ALTER TABLE cicd_deployment ADD COLUMN promoted_from_application_id BIGINT;
ALTER TABLE cicd_deployment ADD COLUMN promoted_from_deployment_id BIGINT;

ALTER TABLE cicd_deployment ADD CONSTRAINT fk_cicd_deployment_promoted_application
    FOREIGN KEY (promoted_from_application_id) REFERENCES application (id) ON DELETE SET NULL;
ALTER TABLE cicd_deployment ADD CONSTRAINT fk_cicd_deployment_promoted_deployment
    FOREIGN KEY (promoted_from_deployment_id) REFERENCES cicd_deployment (id) ON DELETE SET NULL;

CREATE INDEX idx_cicd_deployment_promotion_source
    ON cicd_deployment (promoted_from_application_id, promoted_from_deployment_id);
