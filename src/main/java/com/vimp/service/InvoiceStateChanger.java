package com.vimp.service;

import com.vimp.entity.Invoice;
import com.vimp.constants.InvoiceStatus;

import org.springframework.stereotype.Component;

/**
 * The only way to change an invoice's status from outside this package.
 * Used exclusively by the workflow module, which enforces transitions and permissions.
 */
@Component
public class InvoiceStateChanger {

    public void apply(Invoice invoice, InvoiceStatus newStatus) {
        invoice.changeStatus(newStatus);
    }
}
