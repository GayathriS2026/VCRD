package com.vcrd.po.sap;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Illustrative POs only - not real SAP records. */
@Component
@ConditionalOnProperty(name = "vcrd.sap.mode", havingValue = "mock", matchIfMissing = true)
public class MockSapPoGateway implements SapPoGateway {

    private static final Map<String, SapPurchaseOrder> PURCHASE_ORDERS = Map.of(
            "4500012345", new SapPurchaseOrder("4500012345", "V1001", "ABC Industrial Supplies", "1000",
                    "SITE-01", "INR", "RELEASED", List.of(
                            new SapPoLine(10, "MAT-CBL-01", "Cables", new BigDecimal("100"), "M", new BigDecimal("500.00")),
                            new SapPoLine(20, "MAT-SWG-01", "Switchgear", new BigDecimal("10"), "EA", new BigDecimal("20000.00")))),
            "4500012346", new SapPurchaseOrder("4500012346", "V1002", "Sun Power Pvt Ltd", "1000",
                    "SITE-02", "INR", "RELEASED", List.of(
                            new SapPoLine(10, "MAT-SOL-01", "Solar panels", new BigDecimal("100"), "EA", new BigDecimal("5000.00")))),
            "4500012347", new SapPurchaseOrder("4500012347", "V1001", "ABC Industrial Supplies", "1000",
                    "SITE-01", "INR", "NOT_RELEASED", List.of(
                            new SapPoLine(10, "MAT-CBL-02", "Control cables", new BigDecimal("500"), "M", new BigDecimal("120.00")))));

    @Override
    public Optional<SapPurchaseOrder> fetchPurchaseOrder(String poNumber) {
        return Optional.ofNullable(PURCHASE_ORDERS.get(poNumber));
    }
}
