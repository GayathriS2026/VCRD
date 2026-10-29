package com.vcrd.repository;

import com.vcrd.entity.PurchaseOrder;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Optional<PurchaseOrder> findByPoNumber(String poNumber);
}
