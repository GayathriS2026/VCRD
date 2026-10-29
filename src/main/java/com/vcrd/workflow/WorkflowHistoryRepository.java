package com.vcrd.workflow;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowHistoryRepository extends JpaRepository<WorkflowHistory, UUID> {

    List<WorkflowHistory> findByInvoiceIdOrderByOccurredAt(UUID invoiceId);
}
