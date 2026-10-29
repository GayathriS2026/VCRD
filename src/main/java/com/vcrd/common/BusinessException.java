package com.vcrd.common;

/**
 * A business rule was violated. The code is stable and meant for the frontend
 * (e.g. DUPLICATE_INVOICE, PO_NOT_RELEASED, INVALID_TRANSITION).
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
