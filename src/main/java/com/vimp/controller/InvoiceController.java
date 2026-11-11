package com.vimp.controller;

import com.vimp.dto.InvoiceDtos;
import com.vimp.service.InvoiceService;
import com.vimp.constants.InvoiceStatus;

import java.util.UUID;
import java.util.List;
import com.vimp.constants.DocumentCategory;
import com.vimp.constants.InvoiceDisplayStatus;
import com.vimp.dto.InvoiceDtos.DraftInvoiceRequest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vimp.dto.InvoiceDtos.DocumentResponse;
import com.vimp.dto.ExtractionDtos.VerificationResult;
import com.vimp.dto.InvoiceDtos.InvoiceRequest;
import com.vimp.service.InvoiceVerificationService;
import com.vimp.dto.InvoiceDtos.InvoiceResponse;
import com.vimp.dto.InvoiceDtos.InvoiceSummary;
import com.vimp.service.InvoiceService.StoredDocument;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceVerificationService verificationService;

    public InvoiceController(InvoiceService invoiceService, InvoiceVerificationService verificationService) {
        this.invoiceService = invoiceService;
        this.verificationService = verificationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@Valid @RequestBody InvoiceRequest request) {
        return invoiceService.create(request);
    }

    @PostMapping(path = "/drafts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createDraft(@Valid @RequestPart("invoice") DraftInvoiceRequest request,
            @RequestPart("invoiceDocuments") List<MultipartFile> invoiceDocuments,
            @RequestPart(value = "poDocuments", required = false) List<MultipartFile> poDocuments) {
        return invoiceService.createDraft(request, invoiceDocuments, poDocuments);
    }

    @PutMapping("/{id}")
    public InvoiceResponse update(@PathVariable UUID id, @Valid @RequestBody InvoiceRequest request) {
        return invoiceService.update(id, request);
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable UUID id) {
        return invoiceService.get(id);
    }

    @GetMapping
    public Page<InvoiceSummary> list(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String siteCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) InvoiceDisplayStatus displayStatus,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return invoiceService.list(status, poNumber, siteCode, q, displayStatus, pageable);
    }

    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "INVOICE") DocumentCategory category) {
        return invoiceService.uploadDocument(id, file, category);
    }

    /** Extracts fields from the document (PDF text, or OCR for scans) and compares them with the invoice. */
    @PostMapping("/{id}/documents/{documentId}/verify")
    public VerificationResult verify(@PathVariable UUID id, @PathVariable UUID documentId) {
        return verificationService.verify(id, documentId);
    }

    @GetMapping(path = "/{id}/verification", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> verification(@PathVariable UUID id) {
        return jsonOrNoContent(invoiceService.verificationDetails(id));
    }

    /** Exception-queue detail: each failed rule with SAP expected value vs invoice actual value. */
    @GetMapping(path = "/{id}/validation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> validation(@PathVariable UUID id) {
        return jsonOrNoContent(invoiceService.validationDetails(id));
    }

    private static ResponseEntity<String> jsonOrNoContent(String json) {
        return json == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(json);
    }

    @GetMapping("/{id}/documents/{documentId}/content")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable UUID id,
            @PathVariable UUID documentId) {
        StoredDocument doc = invoiceService.loadDocument(id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.metadata().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(doc.metadata().getOriginalFilename()).build().toString())
                .body(doc.content());
    }
}
