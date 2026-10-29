package com.vcrd.repository;

import com.vcrd.entity.Invoice;
import com.vcrd.constants.InvoiceStatus;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    boolean existsByVendorCodeAndVendorInvoiceNoIgnoreCaseAndStatusNotAndIdNot(
            String vendorCode, String vendorInvoiceNo, InvoiceStatus excludedStatus, UUID excludedId);

    boolean existsByVendorCodeAndVendorInvoiceNoIgnoreCaseAndStatusNot(
            String vendorCode, String vendorInvoiceNo, InvoiceStatus excludedStatus);

    @Query(value = "SELECT nextval('invoice_tracking_seq')", nativeQuery = true)
    long nextTrackingSequence();

    /** Quantity already invoiced against a PO line by other, non-rejected invoices. */
    @Query("""
            select coalesce(sum(l.quantity), 0) from InvoiceLine l
            where l.poLine.id = :poLineId and l.invoice.id <> :invoiceId
              and l.invoice.status <> com.vcrd.constants.InvoiceStatus.REJECTED""")
    BigDecimal sumInvoicedQuantity(UUID poLineId, UUID invoiceId);

    /** Invoices waiting on an automatic step (SAP, validation retry, or a document check not yet run). */
    @Query("""
            select i.id from Invoice i
            where i.status in :statuses
               or (i.status = com.vcrd.constants.InvoiceStatus.PENDING_VERIFICATION
                   and i.matchStatus = com.vcrd.constants.MatchStatus.PENDING)
            order by i.updatedAt""")
    List<UUID> findIdsAwaitingProcessing(Collection<InvoiceStatus> statuses, Pageable pageable);
}
