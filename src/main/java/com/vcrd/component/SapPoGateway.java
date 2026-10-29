package com.vcrd.component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Boundary to SAP for purchase-order data. The rest of the application depends only on this
 * interface. Today it is served by {@link MockSapPoGateway}; the real implementation
 * (OData / BAPI / middleware) is added once the SAP team confirms the interface (BRD Q1).
 */
public interface SapPoGateway {

    Optional<SapPurchaseOrder> fetchPurchaseOrder(String poNumber);

    /** PO numbers created or changed in SAP since the given time (for scheduled synchronisation). */
    List<String> findChangedPoNumbers(Instant since);

    record SapPurchaseOrder(
            String poNumber,
            String vendorCode,
            String vendorName,
            String companyCode,
            String siteCode,
            String currency,
            String releaseStatus,
            List<SapPoLine> lines,
            String purchaseGroup,
            String buyerSapId,
            String paymentTermCode,
            String paymentTermText,
            String wbsCode,
            String vendorGstin) {
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
