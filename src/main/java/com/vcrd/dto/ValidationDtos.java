package com.vcrd.dto;

import java.time.Instant;
import java.util.List;

public final class ValidationDtos {

    private ValidationDtos() {
    }

    /** One failed rule, shown in the exception queue as expected (SAP) vs actual (invoice). */
    public record ValidationIssue(String rule, String field, String expected, String actual, String message) {
    }

    public record ValidationResult(boolean passed, List<ValidationIssue> issues, Instant checkedAt) {
    }
}
