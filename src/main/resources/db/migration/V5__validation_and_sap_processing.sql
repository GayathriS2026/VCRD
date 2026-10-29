-- Validation result and SAP request tracking (Z PARK, GR, MIRO).
ALTER TABLE invoice ADD COLUMN validation_details   TEXT;
ALTER TABLE invoice ADD COLUMN validated_at         TIMESTAMP WITH TIME ZONE;
ALTER TABLE invoice ADD COLUMN zpark_correlation_id VARCHAR(64);
ALTER TABLE invoice ADD COLUMN zpark_requested_at   TIMESTAMP WITH TIME ZONE;
ALTER TABLE invoice ADD COLUMN parked_fiscal_year   VARCHAR(4);
ALTER TABLE invoice ADD COLUMN gr_material_doc_no   VARCHAR(20);
ALTER TABLE invoice ADD COLUMN miro_correlation_id  VARCHAR(64);
ALTER TABLE invoice ADD COLUMN miro_requested_at    TIMESTAMP WITH TIME ZONE;
ALTER TABLE invoice ADD COLUMN miro_doc_no          VARCHAR(20);
ALTER TABLE invoice ADD COLUMN miro_fiscal_year     VARCHAR(4);
ALTER TABLE invoice ADD COLUMN sap_messages         TEXT;

CREATE UNIQUE INDEX ux_invoice_zpark_correlation ON invoice (zpark_correlation_id);
CREATE UNIQUE INDEX ux_invoice_miro_correlation ON invoice (miro_correlation_id);
