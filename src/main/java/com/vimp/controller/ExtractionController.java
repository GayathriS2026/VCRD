package com.vimp.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vimp.component.InvoiceFieldParser;
import com.vimp.component.InvoiceTextReader;
import com.vimp.component.InvoiceTextReader.ReadText;
import com.vimp.dto.ExtractionDtos.ExtractedInvoice;
import com.vimp.exception.BusinessException;

/**
 * Try extraction on one invoice file (a single PDF, which may have many pages, or an image)
 * without creating an invoice. Nothing is stored.
 */
@RestController
@RequestMapping("/api/v1/extraction")
public class ExtractionController {

    private final InvoiceTextReader textReader;
    private final InvoiceFieldParser parser;

    public ExtractionController(InvoiceTextReader textReader, InvoiceFieldParser parser) {
        this.textReader = textReader;
        this.parser = parser;
    }

    public record PreviewResponse(String filename, long elapsedMillis, ExtractedInvoice extracted, String text) {
    }

    /** @param includeText also return the raw text read from the file (to see what OCR produced) */
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse preview(@RequestPart("file") MultipartFile upload,
            @RequestParam(defaultValue = "false") boolean includeText) {
        if (upload.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "Upload a non-empty file under the key 'file'");
        }
        long start = System.currentTimeMillis();
        ReadText text;
        try {
            text = textReader.read(upload.getBytes(), upload.getContentType());
        } catch (IOException e) {
            throw new BusinessException("UNREADABLE_DOCUMENT", "Could not read " + upload.getOriginalFilename());
        }
        return new PreviewResponse(upload.getOriginalFilename(), System.currentTimeMillis() - start,
                parser.parse(text.text(), text.source()), includeText ? text.text() : null);
    }
}
