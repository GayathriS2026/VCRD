package com.vcrd.scheduler;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.vcrd.repository.InvoiceRepository;
import com.vcrd.service.InvoiceProcessor;

/**
 * Retries automatic steps: pending document checks, validation when SAP was unavailable, Z PARK/MIRO
 * outcomes, reconciliation of uncertain SAP results, goods receipt and clearing status.
 */
@Component
@ConditionalOnProperty(name = "vcrd.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class InvoiceProcessingJob {

    private static final Logger log = LoggerFactory.getLogger(InvoiceProcessingJob.class);
    private static final int PAGE_SIZE = 100;

    private final InvoiceRepository invoices;
    private final InvoiceProcessor processor;

    public InvoiceProcessingJob(InvoiceRepository invoices, InvoiceProcessor processor) {
        this.invoices = invoices;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${vcrd.jobs.invoice-interval:PT1M}", initialDelayString = "PT30S")
    public void run() {
        int processed = 0;
        for (int page = 0;; page++) {
            List<UUID> ids = invoices.findIdsAwaitingProcessing(InvoiceProcessor.AUTOMATIC,
                    PageRequest.of(page, PAGE_SIZE));
            ids.forEach(processor::process);
            processed += ids.size();
            if (ids.size() < PAGE_SIZE) {
                break;
            }
        }
        if (processed > 0) {
            log.info("Invoice processing job checked {} invoices", processed);
        }
    }
}
