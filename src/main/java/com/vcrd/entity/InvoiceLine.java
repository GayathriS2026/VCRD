package com.vcrd.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.vcrd.entity.PoLine;

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
@Table(name = "invoice_line")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_line_id")
    private PoLine poLine;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private BigDecimal amount;

    protected InvoiceLine() {
    }

    public InvoiceLine(Invoice invoice, PoLine poLine, BigDecimal quantity, BigDecimal unitPrice) {
        this.invoice = invoice;
        this.poLine = poLine;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getId() {
        return id;
    }

    public PoLine getPoLine() {
        return poLine;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
