package com.vimp.service;

import com.vimp.entity.Invoice;
import com.vimp.entity.PoLine;
import com.vimp.dto.PoResponse;
import com.vimp.entity.PurchaseOrder;
import com.vimp.repository.PurchaseOrderRepository;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vimp.exception.NotFoundException;
import com.vimp.component.SapPoGateway;
import com.vimp.component.SapPoGateway.SapPoLine;
import com.vimp.component.SapPoGateway.SapPurchaseOrder;
import com.vimp.dto.CurrentUser;
import com.vimp.component.CurrentUserProvider;

@Service
public class PoService {

    private final PurchaseOrderRepository repository;
    private final SapPoGateway sapGateway;
    private final CurrentUserProvider currentUser;

    public PoService(PurchaseOrderRepository repository, SapPoGateway sapGateway, CurrentUserProvider currentUser) {
        this.repository = repository;
        this.sapGateway = sapGateway;
        this.currentUser = currentUser;
    }

    @Transactional
    public PoResponse getPo(String poNumber, boolean refresh) {
        return PoResponse.from(loadPo(poNumber, refresh));
    }

    /**
     * Returns the cached PO, fetching it from SAP when missing or when a refresh is requested,
     * and checks that the current user may see the PO's site.
     */
    @Transactional
    public PurchaseOrder loadPo(String poNumber, boolean refresh) {
        PurchaseOrder po = repository.findByPoNumber(poNumber)
                .filter(cached -> !refresh)
                .orElseGet(() -> refreshFromSap(poNumber));
        CurrentUser user = currentUser.get();
        if (!user.canAccessSite(po.getSiteCode())) {
            throw new AccessDeniedException("PO belongs to a site outside the user's scope");
        }
        return po;
    }

    /**
     * Copies the PO from SAP into the local cache. No user/site check: used by system jobs and by
     * validation. Deliberately not @Transactional so an SAP failure does not mark the caller's
     * transaction rollback-only; it joins the caller's transaction instead.
     */
    public PurchaseOrder refreshFromSap(String poNumber) {
        SapPurchaseOrder source = sapGateway.fetchPurchaseOrder(poNumber)
                .orElseThrow(() -> new NotFoundException("PO " + poNumber + " not found in SAP"));

        PurchaseOrder po = repository.findByPoNumber(poNumber).orElseGet(() -> new PurchaseOrder(poNumber));
        po.updateHeader(source.vendorCode(), source.vendorName(), source.companyCode(), source.siteCode(),
                source.currency(), source.releaseStatus());
        po.updateCommercialDetails(source.purchaseGroup(), source.buyerSapId(), source.paymentTermCode(),
                source.paymentTermText(), source.wbsCode(), source.vendorGstin(), source.buyerGstin());
        // Lines are updated in place, never deleted: invoice lines may already reference them.
        for (SapPoLine sapLine : source.lines()) {
            PoLine line = po.findLine(sapLine.itemNo()).orElseGet(() -> {
                PoLine created = new PoLine(po, sapLine.itemNo());
                po.addLine(created);
                return created;
            });
            line.update(sapLine.materialCode(), sapLine.description(), sapLine.orderedQty(), sapLine.uom(),
                    sapLine.unitPrice());
        }
        po.recalculateTotal();
        return repository.save(po);
    }
}
