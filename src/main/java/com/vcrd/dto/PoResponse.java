package com.vcrd.dto;

import com.vcrd.entity.PurchaseOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PoResponse(
        String poNumber,
        String vendorCode,
        String vendorName,
        String companyCode,
        String siteCode,
        String currency,
        BigDecimal totalValue,
        String releaseStatus,
        Instant lastSyncedAt,
        String purchaseGroup,
        String buyerSapId,
        String paymentTermCode,
        String paymentTermText,
        String wbsCode,
        String vendorGstin,
        String buyerGstin,
        List<Line> lines) {

    public record Line(int itemNo, String materialCode, String description, BigDecimal orderedQty, String uom,
            BigDecimal unitPrice) {
    }

    public static PoResponse from(PurchaseOrder po) {
        return new PoResponse(po.getPoNumber(), po.getVendorCode(), po.getVendorName(), po.getCompanyCode(),
                po.getSiteCode(), po.getCurrency(), po.getTotalValue(), po.getReleaseStatus(), po.getLastSyncedAt(),
                po.getPurchaseGroup(), po.getBuyerSapId(), po.getPaymentTermCode(), po.getPaymentTermText(),
                po.getWbsCode(), po.getVendorGstin(), po.getBuyerGstin(),
                po.getLines().stream()
                        .map(l -> new Line(l.getItemNo(), l.getMaterialCode(), l.getDescription(), l.getOrderedQty(),
                                l.getUom(), l.getUnitPrice()))
                        .toList());
    }
}
