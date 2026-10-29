-- One invoice lifecycle aligned with the agreed VIMP workflow; SAP stages are now InvoiceStatus values.
DROP INDEX ix_invoice_sap_status;
ALTER TABLE invoice DROP COLUMN sap_status;

-- System-recorded transitions (validation engine / SAP connector) have no user actor.
ALTER TABLE workflow_history ALTER COLUMN actor_id DROP NOT NULL;

-- Map old statuses to the new lifecycle.
UPDATE invoice SET status = CASE status
    WHEN 'DRAFT'                 THEN 'UPLOADED'
    WHEN 'SUBMITTED'             THEN 'PENDING_VERIFICATION'
    WHEN 'EXTRACTING'            THEN 'PENDING_VERIFICATION'
    WHEN 'AWAITING_VERIFICATION' THEN 'PENDING_VERIFICATION'
    WHEN 'VALIDATING'            THEN 'READY_FOR_VALIDATION'
    WHEN 'EXCEPTION_PENDING'     THEN 'VALIDATION_FAILED'
    WHEN 'READY_FOR_JACK_PARK'   THEN 'VALIDATION_PASSED'
    WHEN 'JACK_PARK_IN_PROGRESS' THEN 'ZPARK_IN_PROGRESS'
    WHEN 'SAP_PROCESSING'        THEN 'MIRO_IN_PROGRESS'
    WHEN 'SAP_UNCERTAIN'         THEN 'MIRO_UNCERTAIN'
    WHEN 'SAP_FAILED'            THEN 'APPROVED'
    WHEN 'COMPLETED'             THEN 'CLEARED'
    ELSE status END;

UPDATE workflow_history SET from_status = CASE from_status
    WHEN 'DRAFT'     THEN 'UPLOADED'
    WHEN 'SUBMITTED' THEN 'PENDING_VERIFICATION'
    ELSE from_status END,
  to_status = CASE to_status
    WHEN 'DRAFT'     THEN 'UPLOADED'
    WHEN 'SUBMITTED' THEN 'PENDING_VERIFICATION'
    ELSE to_status END;

-- SEND_FOR_APPROVAL no longer exists; it moved an invoice towards approval.
UPDATE workflow_history SET action = 'VERIFY' WHERE action = 'SEND_FOR_APPROVAL';
