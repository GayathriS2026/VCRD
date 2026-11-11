package com.vimp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (PO sync, invoice processing). Disable with vimp.jobs.enabled=false, e.g. in tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "vimp.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
