package com.vcrd.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Read-only local copy of an SAP purchase order. SAP stays authoritative;
 * lastSyncedAt tells users how fresh this copy is.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    public static final String RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String poNumber;

    @Column(nullable = false)
    private String vendorCode;

    private String vendorName;

    private String companyCode;

    @Column(nullable = false)
    private String siteCode;

    @Column(nullable = false)
    private String currency;

    private BigDecimal totalValue;

    @Column(nullable = false)
    private String releaseStatus;

    @Column(nullable = false)
    private Instant lastSyncedAt;

    private String purchaseGroup;

    private String buyerSapId;

    private String paymentTermCode;

    private String paymentTermText;

    private String wbsCode;

    private String vendorGstin;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL)
    @OrderBy("itemNo")
    private List<PoLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String poNumber) {
        this.poNumber = poNumber;
    }

    public void updateHeader(String vendorCode, String vendorName, String companyCode, String siteCode,
            String currency, String releaseStatus) {
        this.vendorCode = vendorCode;
        this.vendorName = vendorName;
        this.companyCode = companyCode;
        this.siteCode = siteCode;
        this.currency = currency;
        this.releaseStatus = releaseStatus;
        this.lastSyncedAt = Instant.now();
    }

    public void updateCommercialDetails(String purchaseGroup, String buyerSapId, String paymentTermCode,
            String paymentTermText, String wbsCode, String vendorGstin) {
        this.purchaseGroup = purchaseGroup;
        this.buyerSapId = buyerSapId;
        this.paymentTermCode = paymentTermCode;
        this.paymentTermText = paymentTermText;
        this.wbsCode = wbsCode;
        this.vendorGstin = vendorGstin;
    }

    public Optional<PoLine> findLine(int itemNo) {
        return lines.stream().filter(l -> l.getItemNo() == itemNo).findFirst();
    }

    public void addLine(PoLine line) {
        lines.add(line);
    }

    public void recalculateTotal() {
        this.totalValue = lines.stream()
                .map(l -> l.getOrderedQty().multiply(l.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isReleased() {
        return RELEASED.equals(releaseStatus);
    }

    public UUID getId() {
        return id;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public String getVendorCode() {
        return vendorCode;
    }

    public String getVendorName() {
        return vendorName;
    }

    public String getCompanyCode() {
        return companyCode;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public String getReleaseStatus() {
        return releaseStatus;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public String getPurchaseGroup() {
        return purchaseGroup;
    }

    public String getBuyerSapId() {
        return buyerSapId;
    }

    public String getPaymentTermCode() {
        return paymentTermCode;
    }

    public String getPaymentTermText() {
        return paymentTermText;
    }

    public String getWbsCode() {
        return wbsCode;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public List<PoLine> getLines() {
        return lines;
    }
}
