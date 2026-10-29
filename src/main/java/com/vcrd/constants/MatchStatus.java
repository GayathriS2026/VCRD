package com.vcrd.constants;

/** Result of comparing the invoice document against the entered invoice details. */
public enum MatchStatus {
    PENDING,
    MATCHED,
    MISMATCH,
    /** Extraction was incomplete or failed sanity checks; a person must verify. */
    NEEDS_REVIEW
}
