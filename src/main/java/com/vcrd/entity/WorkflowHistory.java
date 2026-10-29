package com.vcrd.entity;

import com.vcrd.constants.WorkflowAction;

import java.time.Instant;
import java.util.UUID;

import com.vcrd.constants.InvoiceStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow_history")
public class WorkflowHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private InvoiceStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private InvoiceStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WorkflowAction action;

    @Column(nullable = false, updatable = false)
    private UUID actorId;

    @Column(updatable = false)
    private String remark;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    protected WorkflowHistory() {
    }

    public WorkflowHistory(UUID invoiceId, InvoiceStatus fromStatus, InvoiceStatus toStatus, WorkflowAction action,
            UUID actorId, String remark) {
        this.invoiceId = invoiceId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.actorId = actorId;
        this.remark = remark;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public InvoiceStatus getFromStatus() {
        return fromStatus;
    }

    public InvoiceStatus getToStatus() {
        return toStatus;
    }

    public WorkflowAction getAction() {
        return action;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getRemark() {
        return remark;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
