package com.vcrd.component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for SAP. Illustrative only. To exercise failure paths, put one of these in the
 * vendor invoice number:
 * - "SAP-REJECT": SAP refuses the park request.
 * - "SAP-TIMEOUT": SAP parks the document but the response is lost (tests reconciliation).
 * Goods receipt: PO 4500012345 has a GR; other POs do not require one.
 */
@Component
@ConditionalOnProperty(name = "vcrd.sap.mode", havingValue = "mock", matchIfMissing = true)
public class MockSapInvoiceGateway implements SapInvoiceGateway {

    private static final String MOCK_USER = "VIMP_RFC";

    private final Map<String, SapDocument> parked = new ConcurrentHashMap<>();
    private final Map<String, SapDocument> posted = new ConcurrentHashMap<>();
    private final AtomicLong parkSeq = new AtomicLong(2600000000L);
    private final AtomicLong postSeq = new AtomicLong(5100000000L);

    @Override
    public SapDocument parkInvoice(ParkRequest request) {
        String invoiceNo = request.vendorInvoiceNo().toUpperCase();
        if (invoiceNo.contains("SAP-REJECT")) {
            throw new SapRejectedException(List.of("M8 082: Vendor invoice data inconsistent with PO (mock)"));
        }
        SapDocument doc = parked.computeIfAbsent(request.correlationId(), id -> new SapDocument(
                String.valueOf(parkSeq.incrementAndGet()), String.valueOf(request.invoiceDate().getYear()),
                MOCK_USER, "VIMP Interface", Instant.now(), request.totalAmount(),
                List.of("Document parked (mock)")));
        if (invoiceNo.contains("SAP-TIMEOUT")) {
            throw new IllegalStateException("Read timed out (mock): document was parked but response lost");
        }
        return doc;
    }

    @Override
    public Optional<SapDocument> findParkedByCorrelationId(String correlationId) {
        return Optional.ofNullable(parked.get(correlationId));
    }

    @Override
    public GoodsReceipt goodsReceiptStatus(String poNumber, List<Integer> itemNos) {
        return "4500012345".equals(poNumber)
                ? new GoodsReceipt(GrState.RECEIVED, "5000012345")
                : new GoodsReceipt(GrState.NOT_REQUIRED, null);
    }

    @Override
    public SapDocument postInvoice(PostRequest request) {
        SapDocument park = parked.values().stream()
                .filter(d -> d.docNo().equals(request.parkedDocNo()))
                .findFirst()
                .orElseThrow(() -> new SapRejectedException(List.of("Parked document " + request.parkedDocNo()
                        + " not found (mock)")));
        return posted.computeIfAbsent(request.correlationId(), id -> new SapDocument(
                String.valueOf(postSeq.incrementAndGet()), request.parkedFiscalYear(), MOCK_USER,
                "VIMP Interface", Instant.now(), park.amount(), List.of("Invoice posted (mock)")));
    }

    @Override
    public Optional<SapDocument> findPostedByCorrelationId(String correlationId) {
        return Optional.ofNullable(posted.get(correlationId));
    }

    @Override
    public Optional<Clearing> clearingStatus(String postedDocNo, String fiscalYear) {
        // Mock: payment clears as soon as the invoice is posted.
        return posted.values().stream()
                .filter(d -> d.docNo().equals(postedDocNo))
                .findFirst()
                .map(d -> new Clearing("20" + d.docNo().substring(2), LocalDate.now(), d.amount()));
    }
}
