-- Document-vs-entered-details verification result and SAP processing stage.
ALTER TABLE invoice ADD COLUMN sap_status       VARCHAR(20);
ALTER TABLE invoice ADD COLUMN match_status     VARCHAR(20) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE invoice ADD COLUMN match_checked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE invoice ADD COLUMN match_details    TEXT;

CREATE INDEX ix_invoice_match_status ON invoice (match_status);
CREATE INDEX ix_invoice_sap_status ON invoice (sap_status);
