package com.vcrd.po;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vcrd.common.NotFoundException;
import com.vcrd.po.sap.SapPoGateway;
import com.vcrd.po.sap.SapPoGateway.SapPoLine;
import com.vcrd.po.sap.SapPoGateway.SapPurchaseOrder;
import com.vcrd.security.CurrentUser;
import com.vcrd.security.CurrentUserProvider;

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
                .orElseGet(() -> syncFromSap(poNumber));
        CurrentUser user = currentUser.get();
        if (!user.canAccessSite(po.getSiteCode())) {
            throw new AccessDeniedException("PO belongs to a site outside the user's scope");
        }
        return po;
    }

    private PurchaseOrder syncFromSap(String poNumber) {
        SapPurchaseOrder source = sapGateway.fetchPurchaseOrder(poNumber)
                .orElseThrow(() -> new NotFoundException("PO " + poNumber + " not found in SAP"));

        PurchaseOrder po = repository.findByPoNumber(poNumber).orElseGet(() -> new PurchaseOrder(poNumber));
        po.updateHeader(source.vendorCode(), source.vendorName(), source.companyCode(), source.siteCode(),
                source.currency(), source.releaseStatus());
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
