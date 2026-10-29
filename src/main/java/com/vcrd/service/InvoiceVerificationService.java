package com.vcrd.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcrd.component.CurrentUserProvider;
import com.vcrd.component.DocumentStorage;
import com.vcrd.component.InvoiceFieldParser;
import com.vcrd.component.InvoiceTextReader;
import com.vcrd.component.InvoiceTextReader.ReadText;
import com.vcrd.constants.MatchStatus;
import com.vcrd.dto.ExtractionDtos.ExtractedInvoice;
import com.vcrd.dto.ExtractionDtos.ExtractedLine;
import com.vcrd.dto.ExtractionDtos.TextSource;
import com.vcrd.entity.InvoiceLine;
import com.vcrd.dto.ExtractionDtos.FieldMismatch;
import com.vcrd.dto.ExtractionDtos.VerificationResult;
import com.vcrd.entity.Invoice;
import com.vcrd.entity.InvoiceDocument;
import com.vcrd.exception.BusinessException;
import com.vcrd.exception.NotFoundException;
import com.vcrd.repository.InvoiceDocumentRepository;

/**
 * Extracts fields from an uploaded invoice document and compares them with the details entered
 * for the invoice (and the PO's vendor GSTIN). Stores the outcome as the invoice's matchStatus.
 */
@Service
public class InvoiceVerificationService {

    private final InvoiceAccessPolicy accessPolicy;
    private final InvoiceDocumentRepository documents;
    private final DocumentStorage storage;
    private final InvoiceTextReader textReader;
    private final InvoiceFieldParser parser;
    private final AuditService audit;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public InvoiceVerificationService(InvoiceAccessPolicy accessPolicy, InvoiceDocumentRepository documents,
            DocumentStorage storage, InvoiceTextReader textReader, InvoiceFieldParser parser, AuditService audit,
            CurrentUserProvider currentUser, ObjectMapper objectMapper) {
        this.accessPolicy = accessPolicy;
        this.documents = documents;
        this.storage = storage;
        this.textReader = textReader;
        this.parser = parser;
        this.audit = audit;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public VerificationResult verify(UUID invoiceId, UUID documentId) {
        Invoice invoice = accessPolicy.loadAccessible(invoiceId, currentUser.get());
        InvoiceDocument document = documents.findById(documentId)
                .filter(d -> d.getInvoiceId().equals(invoiceId))
                .orElseThrow(() -> new NotFoundException("Document " + documentId + " not found"));

        VerificationResult result = extractAndCompare(invoice, document);
        invoice.recordMatch(result.matchStatus(), toJson(result));
        audit.record("INVOICE", invoiceId, "DOCUMENT_VERIFIED", Map.of("documentId", documentId,
                "matchStatus", result.matchStatus(), "mismatches", result.mismatches().size()));
        return result;
    }

    /**
     * System step after submission: checks the latest document. Never throws for unreadable documents;
     * those are recorded as NEEDS_REVIEW with the reason, so the user can verify manually.
     */
    public void verifyLatestAsSystem(Invoice invoice) {
        List<InvoiceDocument> docs = documents.findByInvoiceIdOrderByVersionNo(invoice.getId());
        if (docs.isEmpty()) {
            return;
        }
        InvoiceDocument latest = docs.get(docs.size() - 1);
        VerificationResult result;
        String error = null;
        try {
            result = extractAndCompare(invoice, latest);
        } catch (BusinessException e) {
            error = e.getMessage();
            result = new VerificationResult(MatchStatus.NEEDS_REVIEW, null, List.of(), List.of());
        }
        invoice.recordMatch(result.matchStatus(), toJson(result));
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("documentId", latest.getId());
        details.put("matchStatus", result.matchStatus());
        details.put("mismatches", result.mismatches().size());
        if (error != null) {
            details.put("error", error);
        }
        audit.recordSystem("INVOICE", invoice.getId(), "DOCUMENT_VERIFIED", details);
    }

    private VerificationResult extractAndCompare(Invoice invoice, InvoiceDocument document) {
        ReadText text = textReader.read(readBytes(document), document.getContentType());
        ExtractedInvoice extracted = parser.parse(text.text(), text.source());
        return compare(invoice, extracted, DocumentText.of(text.text()));
    }

    /**
     * Searchable form of the document text, used to confirm a value the extractor could not attribute
     * to a label (common with OCR of table cells). Only used when nothing was extracted for that field:
     * a different extracted value is always reported as a mismatch, never overridden by a text hit.
     */
    record DocumentText(String compact) {

        static DocumentText of(String text) {
            return new DocumentText(normalize(text));
        }

        boolean containsInvoiceNo(String invoiceNo) {
            String n = normalize(invoiceNo);
            return n.length() >= 4 && compact.contains(n); // short numbers like "16" would match anywhere
        }

        boolean containsAmount(BigDecimal amount) {
            if (amount == null || amount.signum() == 0) {
                return false;
            }
            BigDecimal a = amount.setScale(2, java.math.RoundingMode.HALF_UP);
            return compact.contains(normalize(indian(a))) || compact.contains(normalize(western(a)))
                    || compact.contains(a.toPlainString());
        }

        boolean containsPoNumber(String poNumber) {
            return compact.contains(normalize(poNumber));
        }

        private static String western(BigDecimal a) {
            return new java.text.DecimalFormat("#,##0.00").format(a);
        }

        /** 1,00,000.00 style grouping. */
        private static String indian(BigDecimal a) {
            String plain = a.toPlainString();
            String whole = plain.substring(0, plain.indexOf('.'));
            String decimals = plain.substring(plain.indexOf('.'));
            if (whole.length() <= 3) {
                return plain;
            }
            String last3 = whole.substring(whole.length() - 3);
            String rest = whole.substring(0, whole.length() - 3).replaceAll("\\B(?=(\\d{2})+$)", ",");
            return rest + "," + last3 + decimals;
        }
    }

    static VerificationResult compare(Invoice invoice, ExtractedInvoice x, DocumentText text) {
        List<FieldMismatch> mismatches = new ArrayList<>();
        List<String> confirmedByText = new ArrayList<>();
        boolean missing = false;

        String enteredNo = invoice.getVendorInvoiceNo();
        if (x.vendorInvoiceNo() == null) {
            if (text.containsInvoiceNo(enteredNo)) {
                confirmedByText.add("vendorInvoiceNo");
            } else {
                missing = true;
            }
        } else if (!normalize(enteredNo).equals(normalize(x.vendorInvoiceNo()))) {
            mismatches.add(new FieldMismatch("vendorInvoiceNo", enteredNo, x.vendorInvoiceNo()));
        }

        String poNumber = invoice.getPurchaseOrder().getPoNumber();
        if (!x.poReferences().isEmpty() && !x.poReferences().contains(poNumber)) {
            mismatches.add(new FieldMismatch("poNumber", poNumber, String.join(", ", x.poReferences())));
        } else if (x.poReferences().isEmpty() && !text.containsPoNumber(poNumber)) {
            missing = true;
        }

        if (x.invoiceDate() == null) {
            missing = true;
        } else if (!x.invoiceDate().equals(invoice.getInvoiceDate())) {
            mismatches.add(new FieldMismatch("invoiceDate", str(invoice.getInvoiceDate()), str(x.invoiceDate())));
        }

        String poGstin = invoice.getPurchaseOrder().getVendorGstin();
        if (poGstin != null && !x.gstins().contains(poGstin.toUpperCase(Locale.ROOT))) {
            mismatches.add(new FieldMismatch("vendorGstin", poGstin, String.join(", ", x.gstins())));
        }

        missing |= money("baseAmount", invoice.getBaseAmount(), x.baseAmount(), text, mismatches, confirmedByText);
        missing |= money("cgstAmount", invoice.getCgstAmount(), x.cgstAmount(), text, mismatches, confirmedByText);
        missing |= money("sgstAmount", invoice.getSgstAmount(), x.sgstAmount(), text, mismatches, confirmedByText);
        missing |= money("igstAmount", invoice.getIgstAmount(), x.igstAmount(), text, mismatches, confirmedByText);
        missing |= money("totalAmount", invoice.getTotalAmount(), x.totalAmount(), text, mismatches, confirmedByText);
        missing |= compareLines(invoice, x, mismatches);

        MatchStatus status = !mismatches.isEmpty() ? MatchStatus.MISMATCH
                : (missing || !x.warnings().isEmpty()) ? MatchStatus.NEEDS_REVIEW
                : MatchStatus.MATCHED;
        return new VerificationResult(status, x, mismatches, confirmedByText);
    }

    /**
     * Each entered line must appear in the document with the same quantity and rate. Extra document rows are
     * not flagged: bundles repeat lines (PO copy, BOQ, cost breakup). Returns true when lines could not be
     * confirmed and a person must check: nothing readable, or a row not found in OCR text (OCR may garble rows,
     * so absence there is not proof of a mismatch; with an exact text layer it is).
     */
    private static boolean compareLines(Invoice invoice, ExtractedInvoice x, List<FieldMismatch> out) {
        if (x.lines() == null || x.lines().isEmpty()) {
            return !invoice.getLines().isEmpty();
        }
        boolean unconfirmed = false;
        for (InvoiceLine entered : invoice.getLines()) {
            boolean found = x.lines().stream()
                    .anyMatch(e -> e.quantity().compareTo(entered.getQuantity()) == 0
                            && e.unitPrice().subtract(entered.getUnitPrice()).abs()
                                    .compareTo(InvoiceFieldParser.TOLERANCE) <= 0);
            if (found) {
                continue;
            }
            if (x.source() == TextSource.OCR) {
                unconfirmed = true;
            } else {
                out.add(new FieldMismatch("line " + entered.getPoLine().getItemNo(),
                        entered.getQuantity() + " x " + entered.getUnitPrice(), "no matching row in document"));
            }
        }
        return unconfirmed;
    }

    /**
     * Returns true when the value was entered but could not be confirmed. Zero and absent are treated alike.
     * When nothing was extracted, the entered amount found verbatim in the document counts as confirmed.
     */
    private static boolean money(String field, BigDecimal entered, BigDecimal extracted, DocumentText text,
            List<FieldMismatch> out, List<String> confirmedByText) {
        if (extracted == null) {
            if (entered == null || entered.signum() == 0) {
                return false;
            }
            if (text.containsAmount(entered)) {
                confirmedByText.add(field);
                return false;
            }
            return true;
        }
        BigDecimal e = entered == null ? BigDecimal.ZERO : entered;
        if (e.subtract(extracted).abs().compareTo(InvoiceFieldParser.TOLERANCE) > 0) {
            out.add(new FieldMismatch(field, str(entered), str(extracted)));
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-/]", "").toUpperCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return Objects.toString(o, null);
    }

    private byte[] readBytes(InvoiceDocument document) {
        try (InputStream in = storage.load(document.getStorageKey()).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException("DOCUMENT_UNAVAILABLE", "Stored document could not be read");
        }
    }

    private String toJson(VerificationResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
