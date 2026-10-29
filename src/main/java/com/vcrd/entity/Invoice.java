package com.vcrd.entity;

import com.vcrd.service.InvoiceStateChanger;
import com.vcrd.constants.InvoiceStatus;
import com.vcrd.service.WorkflowService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.vcrd.entity.PurchaseOrder;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String trackingNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id")
    private PurchaseOrder purchaseOrder;

    @Column(nullable = false)
    private String siteCode;

    @Column(nullable = false)
    private String vendorCode;

    @Column(nullable = false)
    private String vendorInvoiceNo;

    @Column(nullable = false)
    private LocalDate invoiceDate;

    @Column(nullable = false)
    private String currency;

    private BigDecimal baseAmount;

    private BigDecimal taxAmount;

    private BigDecimal totalAmount;

    // GST split (India): intra-state invoices use CGST + SGST, inter-state use IGST.
    private BigDecimal cgstAmount;

    private BigDecimal sgstAmount;

    private BigDecimal igstAmount;

    private BigDecimal otherTaxes;

    private BigDecimal otherCharges;

    private LocalDate invoiceReceiptDate;

    @Column(length = 500)
    private String remarks;

    // SAP parking details (synced from SAP, not user-editable).
    private String parkedDocNo;

    private String parkedBySapId;

    private String parkedByName;

    private Instant parkedAt;

    private String parkChangedBySapId;

    private String parkChangedByName;

    private Instant parkChangedAt;

    // SAP MIGO/MIRO clearing details (synced from SAP, not user-editable).
    private BigDecimal miroValue;

    private String clearingDocNo;

    private LocalDate clearingDate;

    private BigDecimal clearedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(String trackingNo, PurchaseOrder purchaseOrder, UUID createdBy) {
        this.trackingNo = trackingNo;
        this.purchaseOrder = purchaseOrder;
        this.siteCode = purchaseOrder.getSiteCode();
        this.vendorCode = purchaseOrder.getVendorCode();
        this.currency = purchaseOrder.getCurrency();
        this.createdBy = createdBy;
    }

    public void updateDetails(String vendorInvoiceNo, LocalDate invoiceDate, BigDecimal baseAmount,
            BigDecimal taxAmount, BigDecimal totalAmount) {
        this.vendorInvoiceNo = vendorInvoiceNo;
        this.invoiceDate = invoiceDate;
        this.baseAmount = baseAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        touch();
    }

    public void updateTaxBreakup(BigDecimal cgstAmount, BigDecimal sgstAmount, BigDecimal igstAmount,
            BigDecimal otherTaxes, BigDecimal otherCharges) {
        this.cgstAmount = cgstAmount;
        this.sgstAmount = sgstAmount;
        this.igstAmount = igstAmount;
        this.otherTaxes = otherTaxes;
        this.otherCharges = otherCharges;
        touch();
    }

    public void updateReceipt(LocalDate invoiceReceiptDate, String remarks) {
        this.invoiceReceiptDate = invoiceReceiptDate;
        this.remarks = remarks;
        touch();
    }

    public void updateParking(String parkedDocNo, String parkedBySapId, String parkedByName, Instant parkedAt,
            String parkChangedBySapId, String parkChangedByName, Instant parkChangedAt) {
        this.parkedDocNo = parkedDocNo;
        this.parkedBySapId = parkedBySapId;
        this.parkedByName = parkedByName;
        this.parkedAt = parkedAt;
        this.parkChangedBySapId = parkChangedBySapId;
        this.parkChangedByName = parkChangedByName;
        this.parkChangedAt = parkChangedAt;
        touch();
    }

    public void updateClearing(BigDecimal miroValue, String clearingDocNo, LocalDate clearingDate,
            BigDecimal clearedAmount) {
        this.miroValue = miroValue;
        this.clearingDocNo = clearingDocNo;
        this.clearingDate = clearingDate;
        this.clearedAmount = clearedAmount;
        touch();
    }

    public void replaceLines(List<InvoiceLine> newLines) {
        lines.clear();
        lines.addAll(newLines);
        touch();
    }

    /** Only WorkflowService (via InvoiceStateChanger) may change status — do not call elsewhere. */
    public void changeStatus(InvoiceStatus newStatus) {
        this.status = newStatus;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public String getVendorCode() {
        return vendorCode;
    }

    public String getVendorInvoiceNo() {
        return vendorInvoiceNo;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getCgstAmount() {
        return cgstAmount;
    }

    public BigDecimal getSgstAmount() {
        return sgstAmount;
    }

    public BigDecimal getIgstAmount() {
        return igstAmount;
    }

    public BigDecimal getOtherTaxes() {
        return otherTaxes;
    }

    public BigDecimal getOtherCharges() {
        return otherCharges;
    }

    public LocalDate getInvoiceReceiptDate() {
        return invoiceReceiptDate;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getParkedDocNo() {
        return parkedDocNo;
    }

    public String getParkedBySapId() {
        return parkedBySapId;
    }

    public String getParkedByName() {
        return parkedByName;
    }

    public Instant getParkedAt() {
        return parkedAt;
    }

    public String getParkChangedBySapId() {
        return parkChangedBySapId;
    }

    public String getParkChangedByName() {
        return parkChangedByName;
    }

    public Instant getParkChangedAt() {
        return parkChangedAt;
    }

    public BigDecimal getMiroValue() {
        return miroValue;
    }

    public String getClearingDocNo() {
        return clearingDocNo;
    }

    public LocalDate getClearingDate() {
        return clearingDate;
    }

    public BigDecimal getClearedAmount() {
        return clearedAmount;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }
}
