-- Simple intake records are completed before entering the existing SAP workflow.
ALTER TABLE invoice ALTER COLUMN po_id DROP NOT NULL;
ALTER TABLE invoice ALTER COLUMN vendor_code DROP NOT NULL;
ALTER TABLE invoice ALTER COLUMN invoice_date DROP NOT NULL;
ALTER TABLE invoice ALTER COLUMN currency DROP NOT NULL;
ALTER TABLE invoice ADD COLUMN po_number VARCHAR(20);
ALTER TABLE invoice ADD COLUMN site_name VARCHAR(200);
UPDATE invoice SET po_number = (SELECT p.po_number FROM purchase_order p WHERE p.id = invoice.po_id);
UPDATE invoice SET site_name = site_code;
CREATE INDEX ix_invoice_po_number ON invoice (po_number);

ALTER TABLE invoice_document ADD COLUMN category VARCHAR(20) DEFAULT 'INVOICE' NOT NULL;
ALTER TABLE invoice_document ADD CONSTRAINT ck_invoice_document_category CHECK (category IN ('INVOICE', 'PO'));
CREATE INDEX ix_invoice_document_category ON invoice_document (invoice_id, category);
