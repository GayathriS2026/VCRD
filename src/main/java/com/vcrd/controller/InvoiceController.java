package com.vcrd.controller;

import com.vcrd.dto.InvoiceDtos;
import com.vcrd.service.InvoiceService;
import com.vcrd.constants.InvoiceStatus;

import java.util.UUID;

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

import com.vcrd.dto.InvoiceDtos.DocumentResponse;
import com.vcrd.dto.InvoiceDtos.InvoiceRequest;
import com.vcrd.dto.InvoiceDtos.InvoiceResponse;
import com.vcrd.dto.InvoiceDtos.InvoiceSummary;
import com.vcrd.service.InvoiceService.StoredDocument;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@Valid @RequestBody InvoiceRequest request) {
        return invoiceService.create(request);
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
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return invoiceService.list(status, poNumber, siteCode, pageable);
    }

    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return invoiceService.uploadDocument(id, file);
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
