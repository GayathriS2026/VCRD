package com.vcrd.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcrd.dto.ValidationDtos.ValidationIssue;
import com.vcrd.dto.ValidationDtos.ValidationResult;
import com.vcrd.entity.Invoice;
import com.vcrd.entity.InvoiceLine;
import com.vcrd.entity.PoLine;
import com.vcrd.entity.PurchaseOrder;
import com.vcrd.repository.InvoiceRepository;

/**
 * Phase 4: matches the invoice against fresh SAP PO data. Every failed mandatory rule is recorded with
 * the SAP-expected value, the invoice value and a reason, for the exception queue.
 * Callers must run this inside a transaction; an SAP failure propagates so the step can be retried.
 */
@Service
public class InvoiceValidationService {

    /** Rupee rounding allowed on amount comparisons. */
    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("1.00");
    static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.01");

    private final PoService poService;
    private final InvoiceRepository invoices;
    private final ObjectMapper objectMapper;

    public InvoiceValidationService(PoService poService, InvoiceRepository invoices, ObjectMapper objectMapper) {
        this.poService = poService;
        this.invoices = invoices;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(Invoice invoice) {
        PurchaseOrder po = poService.refreshFromSap(invoice.getPurchaseOrder().getPoNumber());
        List<ValidationIssue> issues = new ArrayList<>();

        if (!po.isReleased()) {
            issues.add(new ValidationIssue("PO_RELEASED", "releaseStatus", PurchaseOrder.RELEASED,
                    po.getReleaseStatus(), "PO is no longer released in SAP"));
        }
        if (!Objects.equals(po.getVendorCode(), invoice.getVendorCode())) {
            issues.add(new ValidationIssue("VENDOR_MATCH", "vendorCode", po.getVendorCode(),
                    invoice.getVendorCode(), "Invoice vendor differs from PO vendor"));
        }
        if (!Objects.equals(po.getCurrency(), invoice.getCurrency())) {
            issues.add(new ValidationIssue("CURRENCY_MATCH", "currency", po.getCurrency(), invoice.getCurrency(),
                    "Invoice currency differs from PO currency"));
        }

        checkLines(invoice, issues);
        checkAmounts(invoice, issues);
        checkGstStructure(invoice, issues);

        return new ValidationResult(issues.isEmpty(), issues, Instant.now());
    }

    public String toJson(ValidationResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkLines(Invoice invoice, List<ValidationIssue> issues) {
        if (invoice.getLines().isEmpty()) {
            issues.add(new ValidationIssue("LINES_PRESENT", "lines", "at least one line", "0",
                    "Invoice has no lines"));
            return;
        }
        Map<PoLine, BigDecimal> qtyThisInvoice = new LinkedHashMap<>();
        for (InvoiceLine line : invoice.getLines()) {
            PoLine poLine = line.getPoLine();
            String field = "line " + poLine.getItemNo();
            if (line.getUnitPrice().subtract(poLine.getUnitPrice()).compareTo(PRICE_TOLERANCE) > 0) {
                issues.add(new ValidationIssue("UNIT_PRICE", field + " unitPrice", str(poLine.getUnitPrice()),
                        str(line.getUnitPrice()), "Invoiced rate exceeds PO rate"));
            }
            qtyThisInvoice.merge(poLine, line.getQuantity(), BigDecimal::add);
        }
        for (Map.Entry<PoLine, BigDecimal> e : qtyThisInvoice.entrySet()) {
            PoLine poLine = e.getKey();
            BigDecimal already = invoices.sumInvoicedQuantity(poLine.getId(), invoice.getId());
            BigDecimal available = poLine.getOrderedQty().subtract(already);
            if (e.getValue().compareTo(available) > 0) {
                issues.add(new ValidationIssue("QUANTITY_BALANCE", "line " + poLine.getItemNo() + " quantity",
                        "<= " + str(available), str(e.getValue()),
                        "Exceeds open PO quantity (ordered " + str(poLine.getOrderedQty()) + ", already invoiced "
                                + str(already) + ")"));
            }
        }
    }

    private void checkAmounts(Invoice invoice, List<ValidationIssue> issues) {
        BigDecimal lineTotal = invoice.getLines().stream().map(InvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (invoice.getBaseAmount() == null) {
            issues.add(new ValidationIssue("BASE_AMOUNT", "baseAmount", str(lineTotal), null,
                    "Base amount is missing"));
        } else if (differs(lineTotal, invoice.getBaseAmount())) {
            issues.add(new ValidationIssue("BASE_AMOUNT", "baseAmount", str(lineTotal),
                    str(invoice.getBaseAmount()), "Base amount does not equal the sum of invoice lines"));
        }

        BigDecimal splitTax = sum(invoice.getCgstAmount(), invoice.getSgstAmount(), invoice.getIgstAmount(),
                invoice.getOtherTaxes());
        boolean hasSplit = invoice.getCgstAmount() != null || invoice.getSgstAmount() != null
                || invoice.getIgstAmount() != null || invoice.getOtherTaxes() != null;
        if (hasSplit && invoice.getTaxAmount() != null && differs(splitTax, invoice.getTaxAmount())) {
            issues.add(new ValidationIssue("TAX_AMOUNT", "taxAmount", str(splitTax), str(invoice.getTaxAmount()),
                    "Tax amount does not equal CGST + SGST + IGST + other taxes"));
        }
        BigDecimal tax = hasSplit ? splitTax : nz(invoice.getTaxAmount());
        BigDecimal expectedTotal = sum(invoice.getBaseAmount(), tax, invoice.getOtherCharges());
        if (invoice.getTotalAmount() == null) {
            issues.add(new ValidationIssue("TOTAL_AMOUNT", "totalAmount", str(expectedTotal), null,
                    "Total amount is missing"));
        } else if (differs(expectedTotal, invoice.getTotalAmount())) {
            issues.add(new ValidationIssue("TOTAL_AMOUNT", "totalAmount", str(expectedTotal),
                    str(invoice.getTotalAmount()), "Total does not equal base + taxes + other charges"));
        }
    }

    private void checkGstStructure(Invoice invoice, List<ValidationIssue> issues) {
        BigDecimal cgst = nz(invoice.getCgstAmount());
        BigDecimal sgst = nz(invoice.getSgstAmount());
        BigDecimal igst = nz(invoice.getIgstAmount());
        if (igst.signum() > 0 && (cgst.signum() > 0 || sgst.signum() > 0)) {
            issues.add(new ValidationIssue("GST_STRUCTURE", "igstAmount", "IGST or CGST+SGST, not both",
                    "IGST " + str(igst) + ", CGST " + str(cgst) + ", SGST " + str(sgst),
                    "Inter-state (IGST) and intra-state (CGST/SGST) tax charged together"));
        }
        if (cgst.compareTo(sgst) != 0) {
            issues.add(new ValidationIssue("GST_STRUCTURE", "sgstAmount", str(cgst), str(sgst),
                    "CGST and SGST must be equal"));
        }
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(AMOUNT_TOLERANCE) > 0;
    }

    private static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(nz(v));
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object o) {
        return Objects.toString(o, null);
    }
}
