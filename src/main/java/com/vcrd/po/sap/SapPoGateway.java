package com.vcrd.po.sap;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Boundary to SAP for purchase-order data. The rest of the application depends only on this
 * interface. Today it is served by {@link MockSapPoGateway}; the real implementation
 * (OData / BAPI / middleware) is added once the SAP team confirms the interface (BRD Q1).
 */
public interface SapPoGateway {

    Optional<SapPurchaseOrder> fetchPurchaseOrder(String poNumber);

    record SapPurchaseOrder(
            String poNumber,
            String vendorCode,
            String vendorName,
            String companyCode,
            String siteCode,
            String currency,
            String releaseStatus,
            List<SapPoLine> lines) {
    }

    record SapPoLine(
            int itemNo,
            String materialCode,
            String description,
            BigDecimal orderedQty,
            String uom,
            BigDecimal unitPrice) {
    }
}
