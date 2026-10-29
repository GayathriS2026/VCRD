package com.vcrd.entity;

import com.vcrd.service.InvoiceStateChanger;
import com.vcrd.constants.InvoiceStatus;
import com.vcrd.constants.MatchStatus;
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
    private MatchStatus matchStatus = MatchStatus.PENDING;

    private Instant matchCheckedAt;

    /** JSON of the last VerificationResult (extracted values, mismatches, warnings). */
    @Column(columnDefinition = "TEXT")
    private String matchDetails;

    /** JSON of the last ValidationResult (rule failures with expected vs actual). */
    @Column(columnDefinition = "TEXT")
    private String validationDetails;

    private Instant validatedAt;

    // SAP request tracking. The correlation ID is written BEFORE calling SAP so an unknown outcome
    // can be reconciled by asking SAP for that reference instead of resubmitting.
    private String zparkCorrelationId;

    private Instant zparkRequestedAt;

    private String parkedFiscalYear;

    private String grMaterialDocNo;

    private String miroCorrelationId;

    private Instant miroRequestedAt;

    private String miroDocNo;

    private String miroFiscalYear;

    /** Latest SAP return messages, newline separated. */
    @Column(columnDefinition = "TEXT")
    private String sapMessages;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.UPLOADED;

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

    public void recordMatch(MatchStatus matchStatus, String matchDetails) {
        this.matchStatus = matchStatus;
        this.matchDetails = matchDetails;
        this.matchCheckedAt = Instant.now();
        touch();
    }

    /** Entered details changed, so the previous document comparison no longer applies. */
    public void resetMatch() {
        this.matchStatus = MatchStatus.PENDING;
        this.matchDetails = null;
        this.matchCheckedAt = null;
        touch();
    }

    public void recordValidation(String validationDetails) {
        this.validationDetails = validationDetails;
        this.validatedAt = Instant.now();
        touch();
    }

    /** New Z PARK attempt: fresh correlation ID, not yet sent. */
    public void startZpark(String correlationId) {
        this.zparkCorrelationId = correlationId;
        this.zparkRequestedAt = null;
        touch();
    }

    public void markZparkRequested() {
        this.zparkRequestedAt = Instant.now();
        touch();
    }

    public void recordParked(String docNo, String fiscalYear, String createdBySapId, String createdByName,
            Instant createdAt) {
        this.parkedDocNo = docNo;
        this.parkedFiscalYear = fiscalYear;
        this.parkedBySapId = createdBySapId;
        this.parkedByName = createdByName;
        this.parkedAt = createdAt;
        touch();
    }

    public void recordGoodsReceipt(String materialDocNo) {
        this.grMaterialDocNo = materialDocNo;
        touch();
    }

    public void startMiro(String correlationId) {
        this.miroCorrelationId = correlationId;
        this.miroRequestedAt = null;
        touch();
    }

    public void markMiroRequested() {
        this.miroRequestedAt = Instant.now();
        touch();
    }

    public void recordPosted(String docNo, String fiscalYear, BigDecimal miroValue) {
        this.miroDocNo = docNo;
        this.miroFiscalYear = fiscalYear;
        this.miroValue = miroValue;
        touch();
    }

    public void recordSapMessages(String sapMessages) {
        this.sapMessages = sapMessages;
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

    public MatchStatus getMatchStatus() {
        return matchStatus;
    }

    public Instant getMatchCheckedAt() {
        return matchCheckedAt;
    }

    public String getMatchDetails() {
        return matchDetails;
    }

    public String getValidationDetails() {
        return validationDetails;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public String getZparkCorrelationId() {
        return zparkCorrelationId;
    }

    public Instant getZparkRequestedAt() {
        return zparkRequestedAt;
    }

    public String getParkedFiscalYear() {
        return parkedFiscalYear;
    }

    public String getGrMaterialDocNo() {
        return grMaterialDocNo;
    }

    public String getMiroCorrelationId() {
        return miroCorrelationId;
    }

    public Instant getMiroRequestedAt() {
        return miroRequestedAt;
    }

    public String getMiroDocNo() {
        return miroDocNo;
    }

    public String getMiroFiscalYear() {
        return miroFiscalYear;
    }

    public String getSapMessages() {
        return sapMessages;
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
