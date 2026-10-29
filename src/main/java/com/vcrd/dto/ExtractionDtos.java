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
            List<String> warnings,
            /** Party, PO, project and payment details read across the bundle; null fields were not found. */
            BundleDetails details,
            /** Red flags for the reviewer (draft PO, bill before delivery, address differences, ...). */
            List<ReviewFlag> flags) {
    }

    /** Documents a contractor bill bundle is made of; each page is classified as one of these. */
    public enum DocumentType {
        PAYMENT_ADVICE,
        BILL_CHECKLIST,
        BILL_TRACKING_FORM,
        COVERING_LETTER,
        PAYMENT_RECOMMENDATION,
        TAX_INVOICE,
        BILL_OF_QUANTITIES,
        COST_BREAKUP,
        PURCHASE_ORDER,
        OTHER
    }

    public record PageInfo(int page, DocumentType type) {
    }

    public record BundleDetails(
            List<PageInfo> pages,
            // common fields
            String vendorName,
            String vendorGstin,
            String vendorPan,
            String vendorState,
            String buyerName,
            String buyerGstin,
            String buyerPan,
            String buyerState,
            /** Full PO/WO reference as printed, e.g. "ZSER-8000035454". */
            String poReference,
            LocalDate poDate,
            String projectName,
            String projectLocation,
            String workDescription,
            String runningBillNo,
            boolean finalBill,
            String amendmentPoNo,
            String sacCode,
            String uom,
            BigDecimal amountInWords,
            String amountInWordsText,
            BigDecimal deductions,
            LocalDate submittedDate,
            LocalDate certifiedDate,
            // tax invoice only
            String vendorMobile,
            String buyerPincodeOnInvoice,
            boolean invoiceBankDetailsPresent,
            // purchase order only
            String vendorCode,
            String vendorBankAccount,
            String vendorIfsc,
            String wbsElement,
            LocalDate deliveryDate,
            String paymentTerms,
            String shipToGstin,
            String billToPincodeOnPo,
            String segment,
            String preparedBy,
            boolean poDraft,
            // payment advice only
            String jobNo,
            // cost breakup only
            List<ExtractedLine> costBreakup,
            // bill tracking form only: stages whose date/name/sign cells are blank
            List<String> missingSignoffs) {
    }

    public enum FlagSeverity {
        HIGH,
        MEDIUM,
        INFO
    }

    /** @param passed true for checks that ran and passed (shown so the reviewer sees what was checked) */
    public record ReviewFlag(String code, FlagSeverity severity, boolean passed, String message) {
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
