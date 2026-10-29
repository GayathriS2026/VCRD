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
            result = new VerificationResult(MatchStatus.NEEDS_REVIEW, null, List.of());
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
        return compare(invoice, extracted);
    }

    static VerificationResult compare(Invoice invoice, ExtractedInvoice x) {
        List<FieldMismatch> mismatches = new ArrayList<>();
        boolean missing = false;

        String enteredNo = invoice.getVendorInvoiceNo();
        if (x.vendorInvoiceNo() == null) {
            missing = true;
        } else if (!normalize(enteredNo).equals(normalize(x.vendorInvoiceNo()))) {
            mismatches.add(new FieldMismatch("vendorInvoiceNo", enteredNo, x.vendorInvoiceNo()));
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

        missing |= money("baseAmount", invoice.getBaseAmount(), x.baseAmount(), mismatches);
        missing |= money("cgstAmount", invoice.getCgstAmount(), x.cgstAmount(), mismatches);
        missing |= money("sgstAmount", invoice.getSgstAmount(), x.sgstAmount(), mismatches);
        missing |= money("igstAmount", invoice.getIgstAmount(), x.igstAmount(), mismatches);
        missing |= money("totalAmount", invoice.getTotalAmount(), x.totalAmount(), mismatches);

        MatchStatus status = !mismatches.isEmpty() ? MatchStatus.MISMATCH
                : (missing || !x.warnings().isEmpty()) ? MatchStatus.NEEDS_REVIEW
                : MatchStatus.MATCHED;
        return new VerificationResult(status, x, mismatches);
    }

    /** Returns true when the value was entered but could not be extracted. Zero and absent are treated alike. */
    private static boolean money(String field, BigDecimal entered, BigDecimal extracted, List<FieldMismatch> out) {
        if (extracted == null) {
            return entered != null && entered.signum() != 0;
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
