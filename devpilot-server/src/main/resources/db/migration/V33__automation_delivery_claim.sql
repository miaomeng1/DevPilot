ALTER TABLE automation_webhook_delivery ADD COLUMN claim_token VARCHAR(36);
ALTER TABLE automation_webhook_delivery ADD COLUMN claim_expires_at TIMESTAMP(6);
CREATE INDEX idx_automation_delivery_claim ON automation_webhook_delivery(status, claim_expires_at);
