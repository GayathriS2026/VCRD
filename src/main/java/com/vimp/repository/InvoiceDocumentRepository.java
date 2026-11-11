package com.vimp.repository;

import com.vimp.entity.InvoiceDocument;

import java.util.List;
import java.util.UUID;
import java.util.Collection;
import com.vimp.constants.DocumentCategory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceDocumentRepository extends JpaRepository<InvoiceDocument, UUID> {

    List<InvoiceDocument> findByInvoiceIdOrderByVersionNo(UUID invoiceId);

    List<InvoiceDocument> findByInvoiceIdInOrderByVersionNo(Collection<UUID> invoiceIds);

    long countByInvoiceId(UUID invoiceId);

    long countByInvoiceIdAndCategory(UUID invoiceId, DocumentCategory category);

    List<InvoiceDocument> findByInvoiceIdAndCategoryOrderByVersionNo(UUID invoiceId, DocumentCategory category);

    boolean existsBySha256AndCategoryAndInvoiceIdNot(String sha256, DocumentCategory category, UUID invoiceId);

    boolean existsBySha256AndInvoiceIdNot(String sha256, UUID invoiceId);
}
