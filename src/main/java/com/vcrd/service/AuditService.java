package com.vcrd.service;

import com.vcrd.entity.AuditEvent;
import com.vcrd.repository.AuditEventRepository;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcrd.config.CorrelationIdFilter;
import com.vcrd.dto.CurrentUser;
import com.vcrd.component.CurrentUserProvider;

@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public static final String SYSTEM_ACTOR = "SYSTEM";

    public AuditService(AuditEventRepository repository, CurrentUserProvider currentUser, ObjectMapper objectMapper) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    /** Joins the caller's transaction so the audit row commits or rolls back with the change it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, UUID entityId, String action, Map<String, ?> details) {
        CurrentUser user = currentUser.get();
        String roles = user.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        repository.save(new AuditEvent(entityType, entityId, action, user.id(), user.username(), roles,
                toJson(details), MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    /** For events that must be kept even though the surrounding operation is rejected (e.g. blocked malware). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(String entityType, UUID entityId, String action, Map<String, ?> details) {
        record(entityType, entityId, action, details);
    }

    /** For changes recorded by the system (validation engine / SAP connector) with no logged-in user. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSystem(String entityType, UUID entityId, String action, Map<String, ?> details) {
        repository.save(new AuditEvent(entityType, entityId, action, null, SYSTEM_ACTOR, null, toJson(details),
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise audit details", e);
        }
    }
}
