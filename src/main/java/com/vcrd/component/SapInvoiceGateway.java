package com.vcrd.component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Boundary to SAP for invoice processing (Z PARK, goods receipt, MIRO, clearing). Served by
 * {@link MockSapInvoiceGateway} until the SAP team confirms the interface (RFC/BAPI or OData).
 *
 * Contract every implementation must honour:
 * - Every write carries a correlation ID that SAP stores on the document (e.g. in the reference/header text),
 *   so find*ByCorrelationId can tell whether an earlier request was applied.
 * - A definitive business refusal throws {@link SapRejectedException}.
 * - Any other failure (timeout, connection loss, unexpected response) is an UNKNOWN outcome and must
 *   propagate as an exception other than SapRejectedException; callers then reconcile, never resend.
 */
public interface SapInvoiceGateway {

    SapDocument parkInvoice(ParkRequest request);

    Optional<SapDocument> findParkedByCorrelationId(String correlationId);

    GoodsReceipt goodsReceiptStatus(String poNumber, List<Integer> itemNos);

    SapDocument postInvoice(PostRequest request);

    Optional<SapDocument> findPostedByCorrelationId(String correlationId);

    Optional<Clearing> clearingStatus(String postedDocNo, String fiscalYear);

    record ParkRequest(String correlationId, String trackingNo, String poNumber, String vendorCode,
            String vendorInvoiceNo, LocalDate invoiceDate, String currency, BigDecimal baseAmount,
            BigDecimal cgstAmount, BigDecimal sgstAmount, BigDecimal igstAmount, BigDecimal otherTaxes,
            BigDecimal otherCharges, BigDecimal totalAmount, List<ParkLine> lines, List<String> documentRefs) {
    }

    record ParkLine(int poItemNo, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    record PostRequest(String correlationId, String parkedDocNo, String parkedFiscalYear) {
    }

    record SapDocument(String docNo, String fiscalYear, String createdBySapId, String createdByName,
            Instant createdAt, BigDecimal amount, List<String> messages) {
    }

    enum GrState {
        RECEIVED,
        PENDING,
        NOT_REQUIRED
    }

    record GoodsReceipt(GrState state, String materialDocNo) {
    }

    record Clearing(String clearingDocNo, LocalDate clearingDate, BigDecimal clearedAmount) {
    }

    /** SAP processed the request and refused it; safe to correct and try again. */
    class SapRejectedException extends RuntimeException {

        private final List<String> messages;

        public SapRejectedException(List<String> messages) {
            super(String.join("; ", messages));
            this.messages = List.copyOf(messages);
        }

        public List<String> getMessages() {
            return messages;
        }
    }
}
