package com.vcrd.invoice;

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
            InvoiceStatus status,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<LineResponse> lines,
            List<DocumentResponse> documents) {
    }

    public record LineResponse(int poItemNo, String description, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal amount) {
    }

    public record DocumentResponse(UUID id, int versionNo, String originalFilename, String contentType,
            long sizeBytes, String sha256, Instant uploadedAt) {

        static DocumentResponse from(InvoiceDocument d) {
            return new DocumentResponse(d.getId(), d.getVersionNo(), d.getOriginalFilename(), d.getContentType(),
                    d.getSizeBytes(), d.getSha256(), d.getUploadedAt());
        }
    }

    public record InvoiceSummary(UUID id, String trackingNo, String poNumber, String siteCode,
            String vendorInvoiceNo, LocalDate invoiceDate, BigDecimal totalAmount, String currency,
            InvoiceStatus status, Instant updatedAt) {
    }
}
