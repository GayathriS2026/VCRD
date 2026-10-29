package com.vcrd.service;

import com.vcrd.entity.Invoice;
import com.vcrd.repository.InvoiceRepository;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.vcrd.exception.NotFoundException;
import com.vcrd.dto.CurrentUser;

/**
 * Data-scope check for invoices. Out-of-scope invoices are reported as "not found"
 * so users cannot probe for IDs that belong to other sites.
 */
@Component
public class InvoiceAccessPolicy {

    private final InvoiceRepository invoices;

    public InvoiceAccessPolicy(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    public Invoice loadAccessible(UUID invoiceId, CurrentUser user) {
        return invoices.findById(invoiceId)
                .filter(inv -> user.canAccessSite(inv.getSiteCode()))
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceId + " not found"));
    }
}
