package com.vcrd.constants;

/**
 * Workflow actions. User actions are triggered through the API; system actions record outcomes
 * reported by the validation engine or the SAP connector and cannot be called by users.
 */
public enum WorkflowAction {
    // user actions
    SUBMIT(false),
    VERIFY(false),
    OVERRIDE_EXCEPTION(false),
    INITIATE_ZPARK(false),
    APPROVE(false),
    REJECT(false),
    RETURN(false),
    POST_MIRO(false),

    // system actions
    PASS_VALIDATION(true),
    FAIL_VALIDATION(true),
    SAP_CONFIRMED(true),
    SAP_UNCERTAIN(true),
    SAP_REJECTED(true),
    RECONCILED_FOUND(true),
    RECONCILED_NOT_FOUND(true),
    GR_CONFIRMED(true),
    GR_PENDING(true),
    GR_NOT_REQUIRED(true),
    CLEARING_CONFIRMED(true);

    private final boolean system;

    WorkflowAction(boolean system) {
        this.system = system;
    }

    public boolean isSystem() {
        return system;
    }
}
