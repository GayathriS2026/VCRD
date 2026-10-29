package com.vcrd.service;

import com.vcrd.entity.Invoice;
import com.vcrd.entity.InvoiceDocument;
import com.vcrd.repository.InvoiceDocumentRepository;
import com.vcrd.dto.InvoiceDtos;
import com.vcrd.entity.InvoiceLine;
import com.vcrd.repository.InvoiceRepository;
import com.vcrd.constants.InvoiceStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Year;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.vcrd.service.AuditService;
import com.vcrd.exception.BusinessException;
import com.vcrd.exception.NotFoundException;
import com.vcrd.constants.Role;
import com.vcrd.dto.InvoiceDtos.ClearingInfo;
import com.vcrd.dto.InvoiceDtos.DocumentResponse;
import com.vcrd.dto.InvoiceDtos.ParkingInfo;
import com.vcrd.dto.InvoiceDtos.InvoiceRequest;
import com.vcrd.dto.InvoiceDtos.InvoiceResponse;
import com.vcrd.dto.InvoiceDtos.InvoiceSummary;
import com.vcrd.dto.InvoiceDtos.LineRequest;
import com.vcrd.dto.InvoiceDtos.LineResponse;
import com.vcrd.component.DocumentStorage;
import com.vcrd.entity.PoLine;
import com.vcrd.service.PoService;
import com.vcrd.entity.PurchaseOrder;
import com.vcrd.dto.CurrentUser;
import com.vcrd.component.CurrentUserProvider;

import jakarta.persistence.criteria.Predicate;

@Service
public class InvoiceService {

    /** Accepted upload types until the business confirms the final list. */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf", "pdf",
            "image/png", "png",
            "image/jpeg", "jpg");

    private static final List<Role> CAN_CREATE = List.of(Role.SITE_USER, Role.SITE_MANAGER);

    private final InvoiceRepository invoices;
    private final InvoiceDocumentRepository documents;
    private final InvoiceAccessPolicy accessPolicy;
    private final PoService poService;
    private final DocumentStorage storage;
    private final AuditService audit;
    private final CurrentUserProvider currentUser;

    public InvoiceService(InvoiceRepository invoices, InvoiceDocumentRepository documents,
            InvoiceAccessPolicy accessPolicy, PoService poService, DocumentStorage storage, AuditService audit,
            CurrentUserProvider currentUser) {
        this.invoices = invoices;
        this.documents = documents;
        this.accessPolicy = accessPolicy;
        this.poService = poService;
        this.storage = storage;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public InvoiceResponse create(InvoiceRequest request) {
        CurrentUser user = currentUser.get();
        if (!user.hasAnyRole(CAN_CREATE)) {
            throw new AccessDeniedException("Only site users can create invoices");
        }
        PurchaseOrder po = poService.loadPo(request.poNumber(), false);
        if (!po.isReleased()) {
            throw new BusinessException("PO_NOT_RELEASED",
                    "PO " + po.getPoNumber() + " is not released (status " + po.getReleaseStatus() + ")");
        }
        if (invoices.existsByVendorCodeAndVendorInvoiceNoIgnoreCaseAndStatusNot(
                po.getVendorCode(), request.vendorInvoiceNo(), InvoiceStatus.REJECTED)) {
            throw duplicateInvoice(request.vendorInvoiceNo());
        }

        Invoice invoice = new Invoice(nextTrackingNo(), po, user.id());
        applyRequest(invoice, po, request);
        invoices.save(invoice);

        audit.record("INVOICE", invoice.getId(), "INVOICE_CREATED",
                Map.of("trackingNo", invoice.getTrackingNo(), "poNumber", po.getPoNumber()));
        return toResponse(invoice);
    }

    @Transactional
    public InvoiceResponse update(UUID invoiceId, InvoiceRequest request) {
        CurrentUser user = currentUser.get();
        Invoice invoice = accessPolicy.loadAccessible(invoiceId, user);
        requireEditableBySubmitter(invoice, user);
        PurchaseOrder po = invoice.getPurchaseOrder();
        if (!po.getPoNumber().equals(request.poNumber())) {
            throw new BusinessException("PO_CHANGE_NOT_ALLOWED", "An invoice cannot be moved to a different PO");
        }
        if (invoices.existsByVendorCodeAndVendorInvoiceNoIgnoreCaseAndStatusNotAndIdNot(
                po.getVendorCode(), request.vendorInvoiceNo(), InvoiceStatus.REJECTED, invoice.getId())) {
            throw duplicateInvoice(request.vendorInvoiceNo());
        }
        Map<String, Object> before = snapshot(invoice);
        applyRequest(invoice, po, request);
        if (invoice.getStatus() == InvoiceStatus.PENDING_VERIFICATION) {
            invoice.resetMatch(); // re-check the document against the corrected details
        }
        audit.record("INVOICE", invoice.getId(), "INVOICE_UPDATED", Map.of("before", before, "after", snapshot(invoice)));
        return toResponse(invoice);
    }

    @Transactional
    public DocumentResponse uploadDocument(UUID invoiceId, MultipartFile file) {
        CurrentUser user = currentUser.get();
        Invoice invoice = accessPolicy.loadAccessible(invoiceId, user);
        requireEditableBySubmitter(invoice, user);

        if (file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "Uploaded file is empty");
        }
        String extension = ALLOWED_TYPES.get(file.getContentType());
        if (extension == null) {
            throw new BusinessException("UNSUPPORTED_FILE_TYPE", "Allowed types: PDF, PNG, JPG");
        }
        byte[] content = readBytes(file);
        if (!matchesSignature(content, extension)) {
            throw new BusinessException("FILE_CONTENT_MISMATCH", "File content does not match its declared type");
        }
        String sha256 = sha256(content);
        if (documents.existsBySha256AndInvoiceIdNot(sha256, invoiceId)) {
            throw new BusinessException("DUPLICATE_DOCUMENT",
                    "The same file has already been uploaded for another invoice");
        }

        String key = storage.store(content, extension);
        int versionNo = (int) documents.countByInvoiceId(invoiceId) + 1;
        InvoiceDocument document = documents.save(new InvoiceDocument(invoiceId, versionNo,
                safeFilename(file.getOriginalFilename()), file.getContentType(), content.length, sha256, key,
                user.id()));

        audit.record("INVOICE", invoiceId, "DOCUMENT_UPLOADED",
                Map.of("documentId", document.getId(), "versionNo", versionNo, "sha256", sha256));
        return DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public StoredDocument loadDocument(UUID invoiceId, UUID documentId) {
        accessPolicy.loadAccessible(invoiceId, currentUser.get());
        InvoiceDocument document = documents.findById(documentId)
                .filter(d -> d.getInvoiceId().equals(invoiceId))
                .orElseThrow(() -> new NotFoundException("Document " + documentId + " not found"));
        return new StoredDocument(document, storage.load(document.getStorageKey()));
    }

    public record StoredDocument(InvoiceDocument metadata, org.springframework.core.io.Resource content) {
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID invoiceId) {
        return toResponse(accessPolicy.loadAccessible(invoiceId, currentUser.get()));
    }

    /** Stored JSON of the last document check (null if none). */
    @Transactional(readOnly = true)
    public String verificationDetails(UUID invoiceId) {
        return accessPolicy.loadAccessible(invoiceId, currentUser.get()).getMatchDetails();
    }

    /** Stored JSON of the last validation with expected vs actual per failed rule (null if none). */
    @Transactional(readOnly = true)
    public String validationDetails(UUID invoiceId) {
        return accessPolicy.loadAccessible(invoiceId, currentUser.get()).getValidationDetails();
    }

    @Transactional(readOnly = true)
    public Page<InvoiceSummary> list(InvoiceStatus status, String poNumber, String siteCode, Pageable pageable) {
        CurrentUser user = currentUser.get();
        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (poNumber != null && !poNumber.isBlank()) {
                predicates.add(cb.equal(root.get("purchaseOrder").get("poNumber"), poNumber));
            }
            if (siteCode != null && !siteCode.isBlank()) {
                predicates.add(cb.equal(root.get("siteCode"), siteCode));
            }
            if (!user.hasGlobalAccess()) {
                predicates.add(user.sites().isEmpty()
                        ? cb.disjunction()
                        : root.get("siteCode").in(user.sites()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return invoices.findAll(spec, pageable).map(this::toSummary);
    }

    public InvoiceResponse toResponse(Invoice invoice) {
        List<LineResponse> lines = invoice.getLines().stream()
                .map(l -> new LineResponse(l.getPoLine().getItemNo(), l.getPoLine().getDescription(),
                        l.getQuantity(), l.getUnitPrice(), l.getAmount()))
                .toList();
        List<DocumentResponse> docs = documents.findByInvoiceIdOrderByVersionNo(invoice.getId()).stream()
                .map(DocumentResponse::from)
                .toList();
        return new InvoiceResponse(invoice.getId(), invoice.getTrackingNo(),
                invoice.getPurchaseOrder().getPoNumber(), invoice.getSiteCode(), invoice.getVendorCode(),
                invoice.getVendorInvoiceNo(), invoice.getInvoiceDate(), invoice.getCurrency(),
                invoice.getBaseAmount(), invoice.getTaxAmount(), invoice.getTotalAmount(),
                invoice.getCgstAmount(), invoice.getSgstAmount(), invoice.getIgstAmount(), invoice.getOtherTaxes(),
                invoice.getOtherCharges(), invoice.getInvoiceReceiptDate(), invoice.getRemarks(),
                ParkingInfo.from(invoice), ClearingInfo.from(invoice), InvoiceDtos.SapRefs.from(invoice),
                invoice.getMatchStatus(), invoice.getMatchCheckedAt(), invoice.getStatus(),
                invoice.getCreatedBy(), invoice.getCreatedAt(), invoice.getUpdatedAt(), invoice.getVersion(), lines,
                docs);
    }

    private InvoiceSummary toSummary(Invoice i) {
        return new InvoiceSummary(i.getId(), i.getTrackingNo(), i.getPurchaseOrder().getPoNumber(), i.getSiteCode(),
                i.getVendorInvoiceNo(), i.getInvoiceDate(), i.getTotalAmount(), i.getCurrency(), i.getStatus(),
                i.getUpdatedAt());
    }

    private void applyRequest(Invoice invoice, PurchaseOrder po, InvoiceRequest request) {
        invoice.updateDetails(request.vendorInvoiceNo().trim(), request.invoiceDate(), request.baseAmount(),
                request.taxAmount(), request.totalAmount());
        invoice.updateTaxBreakup(request.cgstAmount(), request.sgstAmount(), request.igstAmount(),
                request.otherTaxes(), request.otherCharges());
        invoice.updateReceipt(request.invoiceReceiptDate(), request.remarks());
        List<InvoiceLine> lines = new ArrayList<>();
        for (LineRequest lr : request.lines()) {
            PoLine poLine = po.findLine(lr.poItemNo())
                    .orElseThrow(() -> new BusinessException("UNKNOWN_PO_ITEM",
                            "PO " + po.getPoNumber() + " has no item " + lr.poItemNo()));
            lines.add(new InvoiceLine(invoice, poLine, lr.quantity(), lr.unitPrice()));
        }
        invoice.replaceLines(lines);
    }

    private void requireEditableBySubmitter(Invoice invoice, CurrentUser user) {
        if (!invoice.getStatus().isEditable()) {
            throw new BusinessException("INVOICE_NOT_EDITABLE",
                    "Invoice cannot be changed in status " + invoice.getStatus());
        }
        if (!invoice.getCreatedBy().equals(user.id()) && !user.roles().contains(Role.SITE_MANAGER)) {
            throw new AccessDeniedException("Only the submitter or a site manager can change this invoice");
        }
    }

    private String nextTrackingNo() {
        return "VCRD-%d-%06d".formatted(Year.now().getValue(), invoices.nextTrackingSequence());
    }

    private static BusinessException duplicateInvoice(String vendorInvoiceNo) {
        return new BusinessException("DUPLICATE_INVOICE",
                "Invoice number " + vendorInvoiceNo + " already exists for this vendor");
    }

    private static Map<String, Object> snapshot(Invoice i) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("vendorInvoiceNo", i.getVendorInvoiceNo());
        m.put("invoiceDate", String.valueOf(i.getInvoiceDate()));
        m.put("baseAmount", String.valueOf(i.getBaseAmount()));
        m.put("taxAmount", String.valueOf(i.getTaxAmount()));
        m.put("cgstAmount", String.valueOf(i.getCgstAmount()));
        m.put("sgstAmount", String.valueOf(i.getSgstAmount()));
        m.put("igstAmount", String.valueOf(i.getIgstAmount()));
        m.put("otherTaxes", String.valueOf(i.getOtherTaxes()));
        m.put("otherCharges", String.valueOf(i.getOtherCharges()));
        m.put("totalAmount", String.valueOf(i.getTotalAmount()));
        m.put("lines", i.getLines().stream()
                .map(l -> l.getPoLine().getItemNo() + ":" + l.getQuantity() + "@" + l.getUnitPrice())
                .toList());
        return m;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Basic magic-number check so a renamed executable is not accepted as a PDF/image. */
    private static boolean matchesSignature(byte[] c, String extension) {
        return switch (extension) {
            case "pdf" -> c.length > 4 && c[0] == '%' && c[1] == 'P' && c[2] == 'D' && c[3] == 'F';
            case "png" -> c.length > 8 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G';
            case "jpg" -> c.length > 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8;
            default -> false;
        };
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "invoice";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
