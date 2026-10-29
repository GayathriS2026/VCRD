-- Fields from the SAP "Display Parked Document" screen.

-- Purchase order commercial details
ALTER TABLE purchase_order ADD COLUMN purchase_group    VARCHAR(10);
ALTER TABLE purchase_order ADD COLUMN buyer_sap_id      VARCHAR(40);
ALTER TABLE purchase_order ADD COLUMN payment_term_code VARCHAR(10);
ALTER TABLE purchase_order ADD COLUMN payment_term_text VARCHAR(100);
ALTER TABLE purchase_order ADD COLUMN wbs_code          VARCHAR(40);
ALTER TABLE purchase_order ADD COLUMN vendor_gstin      VARCHAR(15);

-- GST split and receipt details
ALTER TABLE invoice ADD COLUMN cgst_amount          NUMERIC(18, 2);
ALTER TABLE invoice ADD COLUMN sgst_amount          NUMERIC(18, 2);
ALTER TABLE invoice ADD COLUMN igst_amount          NUMERIC(18, 2);
ALTER TABLE invoice ADD COLUMN other_taxes          NUMERIC(18, 2);
ALTER TABLE invoice ADD COLUMN other_charges        NUMERIC(18, 2);
ALTER TABLE invoice ADD COLUMN invoice_receipt_date DATE;
ALTER TABLE invoice ADD COLUMN remarks              VARCHAR(500);

-- SAP parking details
ALTER TABLE invoice ADD COLUMN parked_doc_no          VARCHAR(20);
ALTER TABLE invoice ADD COLUMN parked_by_sap_id       VARCHAR(40);
ALTER TABLE invoice ADD COLUMN parked_by_name         VARCHAR(100);
ALTER TABLE invoice ADD COLUMN parked_at              TIMESTAMP WITH TIME ZONE;
ALTER TABLE invoice ADD COLUMN park_changed_by_sap_id VARCHAR(40);
ALTER TABLE invoice ADD COLUMN park_changed_by_name   VARCHAR(100);
ALTER TABLE invoice ADD COLUMN park_changed_at        TIMESTAMP WITH TIME ZONE;

-- SAP MIGO/MIRO clearing details
ALTER TABLE invoice ADD COLUMN miro_value      NUMERIC(18, 2);
ALTER TABLE invoice ADD COLUMN clearing_doc_no VARCHAR(20);
ALTER TABLE invoice ADD COLUMN clearing_date   DATE;
ALTER TABLE invoice ADD COLUMN cleared_amount  NUMERIC(18, 2);

CREATE INDEX ix_invoice_parked_doc ON invoice (parked_doc_no);
