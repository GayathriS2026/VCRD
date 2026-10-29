package com.vcrd.repository;

import com.vcrd.entity.AuditEvent;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAt(String entityType, UUID entityId);
}
