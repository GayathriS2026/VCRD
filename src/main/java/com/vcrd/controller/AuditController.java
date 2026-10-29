package com.vcrd.controller;

import com.vcrd.entity.AuditEvent;
import com.vcrd.repository.AuditEventRepository;
import com.vcrd.entity.Invoice;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditEventRepository repository;

    public AuditController(AuditEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','PORTAL_ADMIN')")
    public List<AuditEvent> byEntity(@RequestParam(defaultValue = "INVOICE") String entityType,
            @RequestParam UUID entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByOccurredAt(entityType, entityId);
    }
}
