package com.vcrd.repository;

import com.vcrd.entity.WorkflowHistory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowHistoryRepository extends JpaRepository<WorkflowHistory, UUID> {

    List<WorkflowHistory> findByInvoiceIdOrderByOccurredAt(UUID invoiceId);
}
