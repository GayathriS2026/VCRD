package com.vimp.service;

import com.vimp.entity.Invoice;
import com.vimp.entity.InvoiceDocument;
import com.vimp.repository.InvoiceDocumentRepository;
import com.vimp.dto.InvoiceDtos;
import com.vimp.entity.InvoiceLine;
import com.vimp.repository.InvoiceRepository;
import com.vimp.constants.InvoiceStatus;
import com.vimp.constants.InvoiceDisplayStatus;
import com.vimp.constants.DocumentCategory;
import com.vimp.component.InvoiceUploadValidator;
import com.vimp.dto.InvoiceDtos.DraftInvoiceRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Year;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.vimp.service.AuditService;
import com.vimp.exception.BusinessException;
import com.vimp.exception.NotFoundException;
import com.vimp.constants.Role;
import com.vimp.component.MalwareScanner;
import com.vimp.config.WorkflowProperties;
import com.vimp.dto.InvoiceDtos.ApprovalStep;
import com.vimp.dto.InvoiceDtos.ClearingInfo;
import com.vimp.dto.InvoiceDtos.DocumentResponse;
import com.vimp.dto.InvoiceDtos.ParkingInfo;
import com.vimp.dto.InvoiceDtos.InvoiceRequest;
import com.vimp.dto.InvoiceDtos.InvoiceResponse;
import com.vimp.dto.InvoiceDtos.InvoiceSummary;
import com.vimp.dto.InvoiceDtos.LineRequest;
import com.vimp.dto.InvoiceDtos.LineResponse;
import com.vimp.component.DocumentStorage;
import com.vimp.entity.PoLine;
import com.vimp.service.PoService;
import com.vimp.entity.PurchaseOrder;
import com.vimp.dto.CurrentUser;
import com.vimp.component.CurrentUserProvider;

import jakarta.persistence.criteria.Predicate;

@Service
public class InvoiceService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvoiceService.class);

    private static final List<Role> CAN_CREATE = List.of(Role.SITE_USER, Role.SITE_MANAGER);

    private final InvoiceRepository invoices;
    private final InvoiceDocumentRepository documents;
    private final InvoiceAccessPolicy accessPolicy;
    private final PoService poService;
    private final DocumentStorage storage;
    private final AuditService audit;
    private final CurrentUserProvider currentUser;
    private final WorkflowProperties workflowProperties;
    private final MalwareScanner malwareScanner;
    private final InvoiceUploadValidator uploadValidator;

    public InvoiceService(InvoiceRepository invoices, InvoiceDocumentRepository documents,
            InvoiceAccessPolicy accessPolicy, PoService poService, DocumentStorage storage, AuditService audit,
            CurrentUserProvider currentUser, WorkflowProperties workflowProperties,
            MalwareScanner malwareScanner, InvoiceUploadValidator uploadValidator) {
        this.invoices = invoices;
        this.documents = documents;
        this.accessPolicy = accessPolicy;
        this.poService = poService;
        this.storage = storage;
        this.audit = audit;
        this.currentUser = currentUser;
        this.workflowProperties = workflowProperties;
        this.malwareScanner = malwareScanner;
        this.uploadValidator = uploadValidator;
    }

    @Transactional
    public InvoiceResponse createDraft(DraftInvoiceRequest request, List<MultipartFile> invoiceFiles,
            List<MultipartFile> poFiles) {
        CurrentUser user = currentUser.get();
        if (!user.hasAnyRole(CAN_CREATE) || !user.canAccessSite(request.siteCode().trim())) {
            throw new AccessDeniedException("Only site users can register invoices for their assigned sites");
        }
        if (invoiceFiles == null || invoiceFiles.isEmpty()) {
            throw new BusinessException("DOCUMENT_REQUIRED", "At least one invoice document is required");
        }
        String number = request.invoiceNumber().trim();
        if (invoices.existsBySiteCodeAndVendorInvoiceNoIgnoreCaseAndStatusNot(
                request.siteCode().trim(), number, InvoiceStatus.REJECTED)) {
            throw duplicateInvoice(number);
        }
        // Validate the entire batch before writing any files.
        List<PreparedDocument> prepared = new ArrayList<>();
        for (MultipartFile file : invoiceFiles) prepared.add(prepare(file, DocumentCategory.INVOICE));
        if (poFiles != null) {
            for (MultipartFile file : poFiles) prepared.add(prepare(file, DocumentCategory.PO));
        }
        Invoice invoice = invoices.save(new Invoice(nextTrackingNo(), number, request.siteCode().trim(),
                request.siteName().trim(), blankToNull(request.poNumber()), request.remarks(), user.id()));
        // Scan and check duplicates before storing even the first file.
        for (PreparedDocument document : prepared) checkUpload(invoice.getId(), document);
        int version = 0;
        for (PreparedDocument document : prepared) storeDocument(invoice, document, ++version, user);
        audit.record("INVOICE", invoice.getId(), "INVOICE_DRAFT_CREATED",
                Map.of("trackingNo", invoice.getTrackingNo(), "invoiceNumber", number,
                        "siteCode", invoice.getSiteCode(), "documentCount", prepared.size()));
        return toResponse(invoice);
    }

    @Transactional
    public InvoiceResponse create(InvoiceRequest request) {
        CurrentUser user = currentUser.get();
        if (!user.hasAnyRole(CAN_CREATE)) {
            throw new AccessDeniedException("Only site users can create invoices");
        }
        PurchaseOrder po = poService.loadPo(request.poNumber(), false);
        requireReleased(po);
        if (invoices.existsByVendorCodeAndVendorInvoiceNoIgnoreCaseAndStatusNot(
                po.getVendorCode(), request.vendorInvoiceNo().trim(), InvoiceStatus.REJECTED)) {
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
        if (invoice.getStatus() == InvoiceStatus.DRAFT && po == null) {
            po = poService.loadPo(request.poNumber().trim(), false);
            requireReleased(po);
            if (!invoice.getSiteCode().equals(po.getSiteCode())) {
                throw new BusinessException("PO_SITE_MISMATCH", "The PO must belong to the invoice's site");
            }
            if (invoice.getPoNumber() != null && !invoice.getPoNumber().equals(po.getPoNumber())) {
                throw new BusinessException("PO_CHANGE_NOT_ALLOWED", "The PO must match the number entered at intake");
            }
        }
        if (!po.getPoNumber().equals(request.poNumber().trim())) {
            throw new BusinessException("PO_CHANGE_NOT_ALLOWED", "An invoice cannot be moved to a different PO");
        }
        if (invoices.existsByVendorCodeAndVendorInvoiceNoIgnoreCaseAndStatusNotAndIdNot(
                po.getVendorCode(), request.vendorInvoiceNo().trim(), InvoiceStatus.REJECTED, invoice.getId())) {
            throw duplicateInvoice(request.vendorInvoiceNo());
        }
        Map<String, Object> before = snapshot(invoice);
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            requireReleased(po);
            invoice.linkPurchaseOrder(po);
        }
        applyRequest(invoice, po, request);
        // Any edit invalidates a prior comparison, including one performed before submission.
        invoice.resetMatch();
        audit.record("INVOICE", invoice.getId(), "INVOICE_UPDATED", Map.of("before", before, "after", snapshot(invoice)));
        return toResponse(invoice);
    }

    @Transactional
    public DocumentResponse uploadDocument(UUID invoiceId, MultipartFile file) {
        return uploadDocument(invoiceId, file, DocumentCategory.INVOICE);
    }

    @Transactional
    public DocumentResponse uploadDocument(UUID invoiceId, MultipartFile file, DocumentCategory category) {
        CurrentUser user = currentUser.get();
        Invoice invoice = accessPolicy.loadAccessible(invoiceId, user);
        requireEditableBySubmitter(invoice, user);

        PreparedDocument prepared = prepare(file, category);
        checkUpload(invoiceId, prepared);
        int versionNo = (int) documents.countByInvoiceId(invoiceId) + 1;
        return DocumentResponse.from(storeDocument(invoice, prepared, versionNo, user));
    }

    private record PreparedDocument(MultipartFile file, byte[] content, String extension,
            String sha256, DocumentCategory category) { }

    private PreparedDocument prepare(MultipartFile file, DocumentCategory category) {
        var validated = uploadValidator.validate(file);
        return new PreparedDocument(file, validated.content(), validated.extension(), sha256(validated.content()), category);
    }

    private void checkUpload(UUID invoiceId, PreparedDocument document) {
        scanForMalware(invoiceId, document.content(), document.file().getOriginalFilename());
        if (document.category() == DocumentCategory.INVOICE && documents.existsBySha256AndCategoryAndInvoiceIdNot(
                document.sha256(), DocumentCategory.INVOICE, invoiceId)) {
            throw new BusinessException("DUPLICATE_DOCUMENT", "The same invoice file has already been uploaded for another invoice");
        }
    }

    private InvoiceDocument storeDocument(Invoice invoice, PreparedDocument prepared, int version, CurrentUser user) {
        String key = storage.store(prepared.content(), prepared.extension());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { storage.delete(key); }
                    catch (RuntimeException e) { log.error("Failed to clean up rolled-back document {}", key, e); }
                }
            }
        });
        InvoiceDocument document = documents.save(new InvoiceDocument(invoice.getId(), version,
                safeFilename(prepared.file().getOriginalFilename()), prepared.file().getContentType(),
                prepared.content().length, prepared.sha256(), key, user.id(), prepared.category()));
        if (prepared.category() == DocumentCategory.INVOICE) invoice.resetMatch();
        else invoice.updateReceipt(invoice.getInvoiceReceiptDate(), invoice.getRemarks());
        audit.record("INVOICE", invoice.getId(), "DOCUMENT_UPLOADED", Map.of("documentId", document.getId(),
                "versionNo", version, "sha256", prepared.sha256(), "category", prepared.category()));
        return document;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireReleased(PurchaseOrder po) {
        if (!po.isReleased()) throw new BusinessException("PO_NOT_RELEASED",
                "PO " + po.getPoNumber() + " is not released (status " + po.getReleaseStatus() + ")");
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
        return list(status, poNumber, siteCode, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceSummary> list(InvoiceStatus status, String poNumber, String siteCode, String q,
            InvoiceDisplayStatus displayStatus, Pageable pageable) {
        CurrentUser user = currentUser.get();
        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (poNumber != null && !poNumber.isBlank()) {
                predicates.add(cb.equal(root.get("poNumber"), poNumber.trim()));
            }
            if (siteCode != null && !siteCode.isBlank()) {
                predicates.add(cb.equal(root.get("siteCode"), siteCode));
            }
            if (displayStatus != null) predicates.add(root.get("status").in(displayStatus.lifecycleStatuses()));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("vendorInvoiceNo")), pattern, '\\'),
                        cb.like(cb.lower(root.get("poNumber")), pattern, '\\'),
                        cb.like(cb.lower(root.get("siteCode")), pattern, '\\'),
                        cb.like(cb.lower(root.get("siteName")), pattern, '\\')));
            }
            if (!user.hasGlobalAccess()) {
                predicates.add(user.sites().isEmpty()
                        ? cb.disjunction()
                        : root.get("siteCode").in(user.sites()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Invoice> page = invoices.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(Invoice::getId).toList();
        Map<UUID, List<InvoiceDocument>> pageDocuments = ids.isEmpty() ? Map.of()
                : documents.findByInvoiceIdInOrderByVersionNo(ids).stream()
                        .collect(Collectors.groupingBy(InvoiceDocument::getInvoiceId));
        return page.map(i -> toSummary(i, pageDocuments.getOrDefault(i.getId(), List.of())));
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
                invoice.getPoNumber(), invoice.getSiteCode(), invoice.getVendorCode(),
                invoice.getVendorInvoiceNo(), invoice.getInvoiceDate(), invoice.getCurrency(),
                invoice.getBaseAmount(), invoice.getTaxAmount(), invoice.getTotalAmount(),
                invoice.getCgstAmount(), invoice.getSgstAmount(), invoice.getIgstAmount(), invoice.getOtherTaxes(),
                invoice.getOtherCharges(), invoice.getInvoiceReceiptDate(), invoice.getRemarks(),
                ParkingInfo.from(invoice), ClearingInfo.from(invoice), InvoiceDtos.SapRefs.from(invoice), approvalStep(invoice),
                invoice.getMatchStatus(), invoice.getMatchCheckedAt(), invoice.getStatus(),
                invoice.getCreatedBy(), invoice.getCreatedAt(), invoice.getUpdatedAt(), invoice.getVersion(), lines,
                docs, invoice.getSiteName(), InvoiceDisplayStatus.from(invoice.getStatus()));
    }

    private void scanForMalware(UUID invoiceId, byte[] content, String filename) {
        String threat;
        try {
            threat = malwareScanner.findThreat(content);
        } catch (MalwareScanner.ScanUnavailableException e) {
            log.error("Malware scan unavailable; upload refused", e);
            throw new BusinessException("MALWARE_SCAN_UNAVAILABLE",
                    "The file could not be scanned for malware right now; please try again later");
        }
        if (threat != null) {
            // Recorded in a separate transaction so the audit entry survives the rejected upload.
            audit.recordInNewTransaction("INVOICE", invoiceId, "MALWARE_BLOCKED",
                    Map.of("threat", threat, "filename", String.valueOf(filename)));
            throw new BusinessException("MALWARE_DETECTED", "The file was rejected: malware detected (" + threat + ")");
        }
    }

    private ApprovalStep approvalStep(Invoice invoice) {
        if (invoice.getStatus() != InvoiceStatus.PENDING_APPROVAL) {
            return null;
        }
        List<WorkflowProperties.ApprovalLevel> levels = workflowProperties.levelsFor(invoice.getTotalAmount());
        int index = Math.min(invoice.getApprovalLevel(), levels.size() - 1);
        WorkflowProperties.ApprovalLevel level = levels.get(index);
        return new ApprovalStep(level.name(), index + 1, levels.size(), level.roles());
    }

    private InvoiceSummary toSummary(Invoice i, List<InvoiceDocument> docs) {
        long invoiceCount = docs.stream().filter(d -> d.getCategory() == DocumentCategory.INVOICE).count();
        return new InvoiceSummary(i.getId(), i.getTrackingNo(), i.getPoNumber(), i.getSiteCode(),
                i.getVendorInvoiceNo(), i.getInvoiceDate(), i.getTotalAmount(), i.getCurrency(), i.getStatus(),
                i.getUpdatedAt(), i.getSiteName(), i.getRemarks(), i.getCreatedAt(),
                InvoiceDisplayStatus.from(i.getStatus()), invoiceCount, docs.size() - invoiceCount,
                docs.stream().map(DocumentResponse::from).toList());
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
        return "VIMP-%d-%06d".formatted(Year.now().getValue(), invoices.nextTrackingSequence());
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
