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
            /** 10-digit PO numbers referenced in the document (e.g. "ZSER-8000035454" gives 8000035454). */
            List<String> poReferences,
            BigDecimal baseAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal totalAmount,
            List<ExtractedLine> lines,
            List<String> warnings) {
    }

    /** A table row accepted only when quantity x rate = amount, so stray numbers are never taken as lines. */
    public record ExtractedLine(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record FieldMismatch(String field, String entered, String extracted) {
    }

    /**
     * @param confirmedByText fields not attributable to a label but whose entered value appears verbatim
     *                        in the document text (e.g. an invoice number OCR read from a table cell)
     */
    public record VerificationResult(MatchStatus matchStatus, ExtractedInvoice extracted,
            List<FieldMismatch> mismatches, List<String> confirmedByText) {
    }
}
