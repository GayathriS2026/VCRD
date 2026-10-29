package com.vcrd.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "po_line")
public class PoLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id")
    private PurchaseOrder purchaseOrder;

    @Column(nullable = false)
    private int itemNo;

    private String materialCode;

    private String description;

    @Column(nullable = false)
    private BigDecimal orderedQty;

    private String uom;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    protected PoLine() {
    }

    public PoLine(PurchaseOrder purchaseOrder, int itemNo) {
        this.purchaseOrder = purchaseOrder;
        this.itemNo = itemNo;
    }

    public void update(String materialCode, String description, BigDecimal orderedQty, String uom,
            BigDecimal unitPrice) {
        this.materialCode = materialCode;
        this.description = description;
        this.orderedQty = orderedQty;
        this.uom = uom;
        this.unitPrice = unitPrice;
    }

    public UUID getId() {
        return id;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public int getItemNo() {
        return itemNo;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getOrderedQty() {
        return orderedQty;
    }

    public String getUom() {
        return uom;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
