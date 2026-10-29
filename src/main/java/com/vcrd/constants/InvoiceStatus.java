package com.vcrd.constants;

import com.vcrd.service.WorkflowService;

/**
 * Invoice lifecycle, aligned with the agreed VIMP workflow (phases 2-8):
 * upload -> verification -> validation -> Z PARK -> goods receipt -> approval -> MIRO -> clearing.
 * Status only changes through WorkflowService; allowed moves are in TransitionTable.
 */
public enum InvoiceStatus {
    /** Phase 2: registered with a tracking ID, document attached, not yet submitted. */
    UPLOADED,
    /** Phase 3: extracted fields awaiting user verification. */
    PENDING_VERIFICATION,
    /** Phase 3 done: verified, waiting for PO/SAP matching. */
    READY_FOR_VALIDATION,
    /** Phase 4: a mandatory rule failed; sits in the exception queue. */
    VALIDATION_FAILED,
    VALIDATION_PASSED,
    /** Phase 5: park request sent to SAP, outcome not yet known. */
    ZPARK_IN_PROGRESS,
    /** Phase 5: SAP did not confirm; reconcile using SAP references, never blindly resubmit. */
    ZPARK_UNCERTAIN,
    ZPARK_CREATED,
    /** Phase 6: goods receipt required but not yet posted in SAP. */
    AWAITING_GR,
    /** Phase 7. */
    PENDING_APPROVAL,
    APPROVED,
    MIRO_IN_PROGRESS,
    MIRO_UNCERTAIN,
    /** Phase 8: invoice posted in SAP; payment not yet cleared. Posting is not clearance. */
    AWAITING_CLEARING,
    /** Final: SAP confirmed clearing. */
    CLEARED,
    RETURNED,
    REJECTED;

    /** States in which the submitter may still edit details and documents. */
    public boolean isEditable() {
        return this == UPLOADED || this == PENDING_VERIFICATION || this == VALIDATION_FAILED || this == RETURNED;
    }
}
