package com.vcrd.repository;

import com.vcrd.entity.InvoiceDocument;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceDocumentRepository extends JpaRepository<InvoiceDocument, UUID> {

    List<InvoiceDocument> findByInvoiceIdOrderByVersionNo(UUID invoiceId);

    long countByInvoiceId(UUID invoiceId);

    boolean existsBySha256AndInvoiceIdNot(String sha256, UUID invoiceId);
}
