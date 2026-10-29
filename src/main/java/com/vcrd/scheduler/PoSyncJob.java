package com.vcrd.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vcrd.component.SapPoGateway;
import com.vcrd.service.PoService;

/** Phase 1: pulls POs created or changed in SAP so they appear in the work queue without a manual lookup. */
@Component
@ConditionalOnProperty(name = "vcrd.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PoSyncJob {

    private static final Logger log = LoggerFactory.getLogger(PoSyncJob.class);
    /** First run after startup looks back this far; later runs continue from the previous run. */
    private static final Duration INITIAL_LOOKBACK = Duration.ofDays(1);

    private final SapPoGateway sapGateway;
    private final PoService poService;
    private final TransactionTemplate tx;
    private Instant lastRun;

    public PoSyncJob(SapPoGateway sapGateway, PoService poService, PlatformTransactionManager txManager) {
        this.sapGateway = sapGateway;
        this.poService = poService;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${vcrd.jobs.po-sync-interval:PT15M}", initialDelayString = "PT10S")
    public void run() {
        Instant started = Instant.now();
        Instant since = lastRun != null ? lastRun : started.minus(INITIAL_LOOKBACK);
        List<String> changed = sapGateway.findChangedPoNumbers(since);
        int failed = 0;
        for (String poNumber : changed) {
            try {
                tx.executeWithoutResult(s -> poService.refreshFromSap(poNumber));
            } catch (RuntimeException e) {
                failed++;
                log.warn("PO sync failed for {}", poNumber, e);
            }
        }
        // Only move the window forward when everything synced, so failures are picked up next run.
        if (failed == 0) {
            lastRun = started;
        }
        log.info("PO sync: {} changed, {} failed", changed.size(), failed);
    }
}
