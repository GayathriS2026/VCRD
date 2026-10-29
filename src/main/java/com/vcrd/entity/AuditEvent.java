package com.vcrd.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only. No setters; never updated or deleted by the application. */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String entityType;

    @Column(nullable = false, updatable = false)
    private UUID entityId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(updatable = false)
    private UUID actorId;

    @Column(updatable = false)
    private String actorUsername;

    @Column(updatable = false)
    private String actorRoles;

    @Column(updatable = false, columnDefinition = "TEXT")
    private String details;

    @Column(updatable = false)
    private String correlationId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(String entityType, UUID entityId, String action, UUID actorId, String actorUsername,
            String actorRoles, String details, String correlationId) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorId = actorId;
        this.actorUsername = actorUsername;
        this.actorRoles = actorRoles;
        this.details = details;
        this.correlationId = correlationId;
    }

    public UUID getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getActorRoles() {
        return actorRoles;
    }

    public String getDetails() {
        return details;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
