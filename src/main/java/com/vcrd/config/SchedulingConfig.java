package com.vcrd.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (PO sync, invoice processing). Disable with vcrd.jobs.enabled=false, e.g. in tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "vcrd.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
