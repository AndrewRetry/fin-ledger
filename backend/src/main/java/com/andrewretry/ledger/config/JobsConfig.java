package com.andrewretry.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs run only when ledger.jobs.enabled (off in tests, which call the services directly). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledger.jobs.enabled", havingValue = "true")
public class JobsConfig {
}
