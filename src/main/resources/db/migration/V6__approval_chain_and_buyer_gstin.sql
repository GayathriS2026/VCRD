-- Multi-level approval: index into the configured approval levels that apply to the invoice.
ALTER TABLE invoice ADD COLUMN approval_level INTEGER DEFAULT 0 NOT NULL;

-- Receiving plant/company GSTIN from SAP, used to decide intra-state (CGST+SGST) vs inter-state (IGST).
ALTER TABLE purchase_order ADD COLUMN buyer_gstin VARCHAR(15);
