package com.vcrd.dto;

import com.vcrd.constants.MatchStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ExtractionDtos {

    private ExtractionDtos() {
    }

    /** How the text was obtained: embedded PDF text (exact) or OCR (approximate). */
    public enum TextSource {
        PDF_TEXT,
        OCR
    }

    public record ExtractedInvoice(
            TextSource source,
            String vendorInvoiceNo,
            LocalDate invoiceDate,
            /** Every valid GSTIN found, in document order (usually supplier first, then buyer). */
            List<String> gstins,
            BigDecimal baseAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal totalAmount,
            List<String> warnings) {
    }

    public record FieldMismatch(String field, String entered, String extracted) {
    }

    public record VerificationResult(MatchStatus matchStatus, ExtractedInvoice extracted,
            List<FieldMismatch> mismatches) {
    }
}
