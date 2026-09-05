package com.sanedge.gateway.helper;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Dual-read helper for stats cutover (F4/F5). During the transition period,
 * the gateway calls both the old OLTP stats AND the new stats-reader,
 * compares results, and logs mismatches as a metric.
 *
 * <p>Once mismatch = 0 and backfill is complete, switch to stats-reader only
 * and remove the old OLTP stats repos/services/handlers.
 */
@ApplicationScoped
public class StatsDualRead {

    private static final Logger log = LoggerFactory.getLogger(StatsDualRead.class);

    private final LongCounter dualReadTotal;
    private final LongCounter dualReadMismatch;
    private final AtomicLong totalComparisons = new AtomicLong();
    private final AtomicLong totalMismatches = new AtomicLong();

    @Inject
    public StatsDualRead(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("gateway-stats-dual-read");
        this.dualReadTotal = meter.counterBuilder("stats_dual_read_total")
                .setDescription("Total dual-read comparisons performed")
                .build();
        this.dualReadMismatch = meter.counterBuilder("stats_dual_read_mismatch_total")
                .setDescription("Total dual-read mismatches detected")
                .build();
    }

    /**
     * Compare old (OLTP) and new (ClickHouse/stats-reader) results.
     * Logs mismatches and increments metrics for monitoring.
     *
     * @param endpoint   stats endpoint name (for logging)
     * @param oldResult  result from legacy OLTP query
     * @param newResult  result from stats-reader gRPC
     */
    public void compare(String endpoint, Object oldResult, Object newResult) {
        dualReadTotal.add(1);
        totalComparisons.incrementAndGet();

        boolean match = java.util.Objects.equals(oldResult, newResult);
        if (!match) {
            dualReadMismatch.add(1);
            totalMismatches.incrementAndGet();
            log.warn("⚠️ Stats dual-read MISMATCH | endpoint={} old={} new={}",
                    endpoint, truncate(oldResult), truncate(newResult));
        } else {
            log.debug("✅ Stats dual-read match | endpoint={}", endpoint);
        }
    }

    private String truncate(Object obj) {
        if (obj == null) return "null";
        String s = obj.toString();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public long getDualReadTotal() { return totalComparisons.get(); }
    public long getDualReadMismatch() { return totalMismatches.get(); }
}
