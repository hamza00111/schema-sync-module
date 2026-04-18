package com.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the schema sync module.
 *
 * <pre>
 * schema-sync:
 *   platform: postgres          # REQUIRED: postgres | sqlserver
 *   enabled: true
 *   poll-interval-ms: 5000
 *   cdc-retention-days: 3
 *   reconciliation:
 *     enabled: true
 *     cron: "0 0 * * * *"
 *   admin:
 *     enabled: false
 * </pre>
 */
@ConfigurationProperties(prefix = "schema-sync")
public class SyncProperties {

    private Platform platform;
    private boolean enabled = true;
    private long pollIntervalMs = 5000;
    private int cdcRetentionDays = 3;
    private ReconciliationProperties reconciliation = new ReconciliationProperties();

    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getCdcRetentionDays() { return cdcRetentionDays; }
    public void setCdcRetentionDays(int cdcRetentionDays) { this.cdcRetentionDays = cdcRetentionDays; }

    public ReconciliationProperties getReconciliation() { return reconciliation; }
    public void setReconciliation(ReconciliationProperties reconciliation) { this.reconciliation = reconciliation; }

    public enum Platform {
        SQLSERVER,
        POSTGRES
    }

    public static class ReconciliationProperties {
        private boolean enabled = true;
        private String cron = "0 0 * * * *";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }
}
