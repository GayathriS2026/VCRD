package com.vcrd.dto;

import com.vcrd.entity.Invoice;
import com.vcrd.entity.InvoiceDocument;
import com.vcrd.constants.InvoiceStatus;
import com.vcrd.constants.MatchStatus;
import com.vcrd.constants.SapStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    public record InvoiceRequest(
            @NotBlank String poNumber,
            @NotBlank @Size(max = 50) String vendorInvoiceNo,
            @NotNull @PastOrPresent LocalDate invoiceDate,
            @PositiveOrZero BigDecimal baseAmount,
            @PositiveOrZero BigDecimal taxAmount,
            @PositiveOrZero BigDecimal totalAmount,
            @PositiveOrZero BigDecimal cgstAmount,
            @PositiveOrZero BigDecimal sgstAmount,
            @PositiveOrZero BigDecimal igstAmount,
            @PositiveOrZero BigDecimal otherTaxes,
            @PositiveOrZero BigDecimal otherCharges,
            @PastOrPresent LocalDate invoiceReceiptDate,
            @Size(max = 500) String remarks,
            @NotEmpty List<@Valid LineRequest> lines) {
    }

    public record LineRequest(
            @NotNull Integer poItemNo,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @PositiveOrZero BigDecimal unitPrice) {
    }

    public record InvoiceResponse(
            UUID id,
            String trackingNo,
            String poNumber,
            String siteCode,
            String vendorCode,
            String vendorInvoiceNo,
            LocalDate invoiceDate,
            String currency,
            BigDecimal baseAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal otherTaxes,
            BigDecimal otherCharges,
            LocalDate invoiceReceiptDate,
            String remarks,
            ParkingInfo parking,
            ClearingInfo clearing,
            SapStatus sapStatus,
            MatchStatus matchStatus,
            Instant matchCheckedAt,
            InvoiceStatus status,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<LineResponse> lines,
            List<DocumentResponse> documents) {
    }

    public record ParkingInfo(String parkedDocNo, String parkedBySapId, String parkedByName, Instant parkedAt,
            String changedBySapId, String changedByName, Instant changedAt) {

        public static ParkingInfo from(Invoice i) {
            return new ParkingInfo(i.getParkedDocNo(), i.getParkedBySapId(), i.getParkedByName(), i.getParkedAt(),
                    i.getParkChangedBySapId(), i.getParkChangedByName(), i.getParkChangedAt());
        }
    }

    public record ClearingInfo(BigDecimal miroValue, String clearingDocNo, LocalDate clearingDate,
            BigDecimal clearedAmount) {

        public static ClearingInfo from(Invoice i) {
            return new ClearingInfo(i.getMiroValue(), i.getClearingDocNo(), i.getClearingDate(),
                    i.getClearedAmount());
        }
    }

    public record LineResponse(int poItemNo, String description, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal amount) {
    }

    public record DocumentResponse(UUID id, int versionNo, String originalFilename, String contentType,
            long sizeBytes, String sha256, Instant uploadedAt) {

        public static DocumentResponse from(InvoiceDocument d) {
            return new DocumentResponse(d.getId(), d.getVersionNo(), d.getOriginalFilename(), d.getContentType(),
                    d.getSizeBytes(), d.getSha256(), d.getUploadedAt());
        }
    }

    public record InvoiceSummary(UUID id, String trackingNo, String poNumber, String siteCode,
            String vendorInvoiceNo, LocalDate invoiceDate, BigDecimal totalAmount, String currency,
            InvoiceStatus status, Instant updatedAt) {
    }
}
