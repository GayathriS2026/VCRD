package com.vimp.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.vimp.constants.DocumentCategory;

/** Metadata of a stored invoice file. Files are immutable; a correction adds a new version. */
@Entity
@Table(name = "invoice_document")
public class InvoiceDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DocumentCategory category = DocumentCategory.INVOICE;

    @Column(nullable = false, updatable = false)
    private int versionNo;

    @Column(nullable = false, updatable = false)
    private String originalFilename;

    @Column(nullable = false, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(nullable = false, updatable = false)
    private String storageKey;

    @Column(nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    protected InvoiceDocument() {
    }

    public InvoiceDocument(UUID invoiceId, int versionNo, String originalFilename, String contentType,
            long sizeBytes, String sha256, String storageKey, UUID uploadedBy) {
        this.invoiceId = invoiceId;
        this.versionNo = versionNo;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
    }

    public InvoiceDocument(UUID invoiceId, int versionNo, String originalFilename, String contentType,
            long sizeBytes, String sha256, String storageKey, UUID uploadedBy, DocumentCategory category) {
        this(invoiceId, versionNo, originalFilename, contentType, sizeBytes, sha256, storageKey, uploadedBy);
        this.category = category;
    }

    public DocumentCategory getCategory() { return category; }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
