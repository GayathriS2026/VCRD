package com.vcrd.repository;

import com.vcrd.entity.Invoice;
import com.vcrd.constants.InvoiceStatus;

import java.util.UUID;

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
}
