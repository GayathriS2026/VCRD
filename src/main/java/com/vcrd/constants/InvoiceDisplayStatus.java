package com.vcrd.constants;

import java.util.Arrays;
import java.util.List;

/** Coarse labels for the register; accounting transitions remain server-controlled. */
public enum InvoiceDisplayStatus {
    PENDING, PROCESSING, COMPLETED, REJECTED;

    public static InvoiceDisplayStatus from(InvoiceStatus status) {
        return switch (status) {
            case DRAFT, UPLOADED, RETURNED, VALIDATION_FAILED -> PENDING;
            case CLEARED -> COMPLETED;
            case REJECTED -> REJECTED;
            default -> PROCESSING;
        };
    }

    public List<InvoiceStatus> lifecycleStatuses() {
        return Arrays.stream(InvoiceStatus.values()).filter(s -> from(s) == this).toList();
    }
}
