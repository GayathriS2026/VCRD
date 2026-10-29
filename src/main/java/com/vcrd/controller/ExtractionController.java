package com.vcrd.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vcrd.component.InvoiceFieldParser;
import com.vcrd.component.InvoiceTextReader;
import com.vcrd.component.InvoiceTextReader.ReadText;
import com.vcrd.dto.ExtractionDtos.ExtractedInvoice;
import com.vcrd.dto.ExtractionDtos.TextSource;
import com.vcrd.exception.BusinessException;

/**
 * Try extraction on invoice files without creating an invoice. Nothing is stored.
 * Accepts one or more files under the form key "file" (an invoice is often split across files:
 * payment advice, tax invoice, PO copy). Each file is extracted on its own, and all files together
 * give the combined result, read the same way as a single multi-page bundle.
 */
@RestController
@RequestMapping("/api/v1/extraction")
public class ExtractionController {

    private static final int MAX_FILES = 20;

    private final InvoiceTextReader textReader;
    private final InvoiceFieldParser parser;

    public ExtractionController(InvoiceTextReader textReader, InvoiceFieldParser parser) {
        this.textReader = textReader;
        this.parser = parser;
    }

    public record FileResult(String filename, long elapsedMillis, ExtractedInvoice extracted, String text) {
    }

    /**
     * @param combined fields read across all uploaded files together (use this for the invoice)
     * @param files    what each file contains on its own
     */
    public record PreviewResponse(int fileCount, long elapsedMillis, ExtractedInvoice combined, List<FileResult> files) {
    }

    /** @param includeText also return the raw text read from each file (to see what OCR produced) */
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse preview(@RequestPart("file") List<MultipartFile> uploads,
            @RequestParam(defaultValue = "false") boolean includeText) {
        if (uploads.isEmpty() || uploads.stream().allMatch(MultipartFile::isEmpty)) {
            throw new BusinessException("EMPTY_FILE", "Upload at least one non-empty file under the key 'file'");
        }
        if (uploads.size() > MAX_FILES) {
            throw new BusinessException("TOO_MANY_FILES", "At most " + MAX_FILES + " files per request");
        }
        long start = System.currentTimeMillis();
        List<FileResult> results = new ArrayList<>();
        StringBuilder allText = new StringBuilder();
        boolean anyOcr = false;
        for (MultipartFile upload : uploads) {
            if (upload.isEmpty()) {
                continue;
            }
            long fileStart = System.currentTimeMillis();
            ReadText text = read(upload);
            anyOcr |= text.source() == TextSource.OCR;
            allText.append(text.text()).append('\n');
            results.add(new FileResult(upload.getOriginalFilename(), System.currentTimeMillis() - fileStart,
                    parser.parse(text.text(), text.source()), includeText ? text.text() : null));
        }
        ExtractedInvoice combined = parser.parse(allText.toString(), anyOcr ? TextSource.OCR : TextSource.PDF_TEXT);
        return new PreviewResponse(results.size(), System.currentTimeMillis() - start, combined, results);
    }

    private ReadText read(MultipartFile upload) {
        try {
            return textReader.read(upload.getBytes(), upload.getContentType());
        } catch (IOException e) {
            throw new BusinessException("UNREADABLE_DOCUMENT", "Could not read " + upload.getOriginalFilename());
        }
    }
}
