package com.vcrd.invoice;

/**
 * Proposed lifecycle (BRD 4.9). Several states are placeholders for phases not built yet
 * (extraction, validation, JACK PARK, SAP). Status only changes through WorkflowService.
 */
public enum InvoiceStatus {
    DRAFT,
    SUBMITTED,
    EXTRACTING,
    AWAITING_VERIFICATION,
    VALIDATING,
    EXCEPTION_PENDING,
    READY_FOR_JACK_PARK,
    JACK_PARK_IN_PROGRESS,
    PENDING_APPROVAL,
    APPROVED,
    SAP_PROCESSING,
    SAP_UNCERTAIN,
    SAP_FAILED,
    COMPLETED,
    REJECTED,
    RETURNED;

    /** States in which the submitter may still edit details and documents. */
    public boolean isEditable() {
        return this == DRAFT || this == RETURNED;
    }
}
