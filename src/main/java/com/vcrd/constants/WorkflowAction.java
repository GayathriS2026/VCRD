package com.vcrd.constants;

/**
 * Workflow actions. User actions are triggered through the API; system actions record outcomes
 * reported by the validation engine or the SAP connector and cannot be called by users.
 */
public enum WorkflowAction {
    // user actions
    SUBMIT,
    VERIFY,
    OVERRIDE_EXCEPTION,
    INITIATE_ZPARK,
    APPROVE,
    REJECT,
    RETURN,
    POST_MIRO,

    // system actions
    PASS_VALIDATION,
    FAIL_VALIDATION,
    SAP_CONFIRMED,
    SAP_UNCERTAIN,
    RECONCILED_FOUND,
    RECONCILED_NOT_FOUND,
    GR_CONFIRMED,
    GR_PENDING,
    GR_NOT_REQUIRED,
    CLEARING_CONFIRMED
}
