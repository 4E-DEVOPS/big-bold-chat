package com.bigboldchat.debug;

import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * Collects Chat XL performance metrics.
 */
@Slf4j
public final class PerformanceMetrics
{
    private static final long REPORT_INTERVAL_NANOS = 10_000_000_000L;

    private static final int GAME_BODY_SCRIPT = 199;
    private static final int CHAT_BODY_SCRIPT = 203;
    private static final int CHANNEL_BODY_SCRIPT = 4483;

    /*
     * TIMINGS
     */

    // Measure Script PRE processing time.
    private final TimingMetric pre199 =
            new TimingMetric();

    private final TimingMetric pre203 =
            new TimingMetric();

    private final TimingMetric pre4483 =
            new TimingMetric();

    // Measure Script POST processing time.
    private final TimingMetric post199 =
            new TimingMetric();

    private final TimingMetric post203 =
            new TimingMetric();

    private final TimingMetric post4483 =
            new TimingMetric();

    // Measure construction measurement time.
    private final TimingMetric measurement =
            new TimingMetric();

    // Measure semantic text normalization time.
    private final TimingMetric normalization =
            new TimingMetric();

    // Measure uncached FontTypeFace resolution time.
    private final TimingMetric fontResolution =
            new TimingMetric();

    /*
     * COUNTERS
     */

    // Count FontTypeFace cache activity.
    private long fontCacheHits;

    private long fontCacheMisses;

    // Count widgets examined during chat correlation.
    private long widgetsExamined;

    // Count full chat-surface searches.
    private long surfaceSearches;

    // Number of row searches.
    private long rowSearches;

    // Number of row-candidates searches.
    private long rowCandidates;

    // Count correlation fallback requests.
    private long fallbackSearches;

    // Count actual lazy fallback index builds.
    private long fallbackBuilds;

    // Count fallback requests served by an already-built per-POST index.
    private long fallbackReuses;

    // Count rank-icon searches and examined nodes.
    private long rankSearches;

    private long rankNodesExamined;

    // Count widget mutations.
    private long widgetMutations;

    // Count widget revalidation calls.
    private long revalidates;

    // Count initiated chat refreshes.
    private long refreshChatCalls;

    private long refreshStartup;

    private long refreshFontChanged;

    private long refreshShutdown;

    private long refreshWidthChanged;

    private long refreshOther;

    private long reportStartedAt =
            System.nanoTime();

    /*
     * RECORDING
     */

    public void recordPre(
            int scriptId,
            long elapsedNanos)
    {
        final TimingMetric metric =
                preMetric(
                        scriptId);

        if (metric != null)
        {
            metric.record(
                    elapsedNanos);
        }
    }

    public void recordPost(
            int scriptId,
            long elapsedNanos)
    {
        final TimingMetric metric =
                postMetric(
                        scriptId);

        if (metric != null)
        {
            metric.record(
                    elapsedNanos);
        }
    }

    public void recordMeasurement(
            long elapsedNanos)
    {
        measurement.record(
                elapsedNanos);
    }

    public void recordNormalization(
            long elapsedNanos)
    {
        normalization.record(
                elapsedNanos);
    }

    public void recordFontCacheHit()
    {
        fontCacheHits++;
    }

    public void recordFontCacheMiss(
            long elapsedNanos)
    {
        fontCacheMisses++;

        fontResolution.record(
                elapsedNanos);
    }

    public void recordWidgetsExamined(
            int count)
    {
        if (count > 0)
        {
            widgetsExamined +=
                    count;
        }
    }

    public void recordSurfaceSearch()
    {
        surfaceSearches++;
    }

    public void recordRowSearches(
            int candidates)
    {
        rowSearches++;

        if (candidates > 0)
        {
            rowCandidates += candidates;
        }
    }

    public void recordFallbackSearch()
    {
        fallbackSearches++;
    }

    public void recordFallbackBuild()
    {
        fallbackBuilds++;
    }

    public void recordFallbackReuse()
    {
        fallbackReuses++;
    }

    public void recordRankSearch()
    {
        rankSearches++;
    }

    public void recordRankNodesExamined(
            int count)
    {
        if (count > 0)
        {
            rankNodesExamined +=
                    count;
        }
    }

    public void recordWidgetMutation()
    {
        widgetMutations++;
    }

    public void recordRevalidate()
    {
        revalidates++;
    }

    public void recordRefreshChat(
            RefreshReason reason)
    {
        refreshChatCalls++;

        if (reason == null)
        {
            refreshOther++;
            return;
        }

        switch (reason)
        {
            case STARTUP:
                refreshStartup++;
                break;

            case FONT_CHANGED:
                refreshFontChanged++;
                break;

            case SHUTDOWN:
                refreshShutdown++;
                break;

            case WIDTH_CHANGED:
                refreshWidthChanged++;
                break;

            default:
                refreshOther++;
                break;
        }
    }

    /*
     * REPORTING
     */

    public void reportIfDue()
    {
        final long now =
                System.nanoTime();

        if (now
                - reportStartedAt
                < REPORT_INTERVAL_NANOS)
        {
            return;
        }

        report(
                now);
    }

    public void reportNow()
    {
        report(
                System.nanoTime());
    }

    private void report(
            long now)
    {
        final long elapsedNanos =
                now
                        - reportStartedAt;

        if (elapsedNanos <= 0L)
        {
            return;
        }

        final double elapsedSeconds =
                elapsedNanos
                        / 1_000_000_000.0;

        log.info(
                "[Chat XL][Performance] Window={}",
                String.format(
                        Locale.ROOT,
                        "%.3fs",
                        elapsedSeconds));

        log.info(
                "[Chat XL][Performance] PRE"
                        + " | 199={}"
                        + " | 203={}"
                        + " | 4483={}",
                formatTiming(
                        pre199),
                formatTiming(
                        pre203),
                formatTiming(
                        pre4483));

        log.info(
                "[Chat XL][Performance] POST"
                        + " | 199={}"
                        + " | 203={}"
                        + " | 4483={}",
                formatTiming(
                        post199),
                formatTiming(
                        post203),
                formatTiming(
                        post4483));

        log.info(
                "[Chat XL][Performance] SERVICES"
                        + " | Measurement={}"
                        + " | Normalize={}"
                        + " | FontResolve={}"
                        + " | FontCacheHits={}"
                        + " | FontCacheMisses={}",
                formatTiming(
                        measurement),
                formatTiming(
                        normalization),
                formatTiming(
                        fontResolution),
                fontCacheHits,
                fontCacheMisses);

        log.info(
                "[Chat XL][Performance] CORRELATION"
                        + " | Widgets={}"
                        + " | RowSearches={}"
                        + " | RowCandidates={}"
                        + " | SurfaceSearches={}"
                        + " | FallbackSearches={}"
                        + " | FallbackBuilds={}"
                        + " | FallbackReuses={}"
                        + " | RankSearches={}"
                        + " | RankNodes={}",
                widgetsExamined,
                rowSearches,
                rowCandidates,
                surfaceSearches,
                fallbackSearches,
                fallbackBuilds,
                fallbackReuses,
                rankSearches,
                rankNodesExamined);

        log.info(
                "[Chat XL][Performance] PRESENTATION"
                        + " | Mutations={}"
                        + " | Revalidates={}",
                widgetMutations,
                revalidates);

        log.info(
                "[Chat XL][Performance] REFRESH"
                        + " | Total={}"
                        + " | Startup={}"
                        + " | FontChanged={}"
                        + " | Shutdown={}"
                        + " | WidthChanged={}"
                        + " | Other={}",
                refreshChatCalls,
                refreshStartup,
                refreshFontChanged,
                refreshShutdown,
                refreshWidthChanged,
                refreshOther);

        resetWindow(
                now);
    }

    /*
     * HELPERS
     */

    private TimingMetric preMetric(
            int scriptId)
    {
        switch (scriptId)
        {
            case GAME_BODY_SCRIPT:
                return pre199;

            case CHAT_BODY_SCRIPT:
                return pre203;

            case CHANNEL_BODY_SCRIPT:
                return pre4483;

            default:
                return null;
        }
    }

    private TimingMetric postMetric(
            int scriptId)
    {
        switch (scriptId)
        {
            case GAME_BODY_SCRIPT:
                return post199;

            case CHAT_BODY_SCRIPT:
                return post203;

            case CHANNEL_BODY_SCRIPT:
                return post4483;

            default:
                return null;
        }
    }

    private static String formatTiming(
            TimingMetric metric)
    {
        if (metric == null
                || metric.count == 0L)
        {
            return "calls=0 avg=0.000ms max=0.000ms";
        }

        return String.format(
                Locale.ROOT,
                "calls=%d avg=%.6fms max=%.6fms",
                metric.count,
                metric.averageMilliseconds(),
                metric.maxMilliseconds());
    }

    private void resetWindow(
            long now)
    {
        pre199.reset();
        pre203.reset();
        pre4483.reset();

        post199.reset();
        post203.reset();
        post4483.reset();

        measurement.reset();
        normalization.reset();
        fontResolution.reset();

        fontCacheHits =
                0L;

        fontCacheMisses =
                0L;

        widgetsExamined =
                0L;

        rowSearches =
                0L;

        rowCandidates =
                0L;

        surfaceSearches =
                0L;

        fallbackSearches =
                0L;

        fallbackBuilds =
                0L;

        fallbackReuses =
                0L;

        rankSearches =
                0L;

        rankNodesExamined =
                0L;

        widgetMutations =
                0L;

        revalidates =
                0L;

        refreshChatCalls =
                0L;

        refreshStartup =
                0L;

        refreshFontChanged =
                0L;

        refreshShutdown =
                0L;

        refreshWidthChanged =
                0L;

        refreshOther =
                0L;

        reportStartedAt =
                now;
    }

    public enum RefreshReason
    {
        STARTUP,
        FONT_CHANGED,
        SHUTDOWN,
        WIDTH_CHANGED,
        OTHER
    }

    private static final class TimingMetric
    {
        private long count;
        private long totalNanos;
        private long maxNanos;

        private void record(
                long elapsedNanos)
        {
            if (elapsedNanos < 0L)
            {
                return;
            }

            count++;

            totalNanos +=
                    elapsedNanos;

            if (elapsedNanos
                    > maxNanos)
            {
                maxNanos =
                        elapsedNanos;
            }
        }

        private double averageMilliseconds()
        {
            if (count == 0L)
            {
                return 0.0;
            }

            return totalNanos
                    / (double) count
                    / 1_000_000.0;
        }

        private double maxMilliseconds()
        {
            return maxNanos
                    / 1_000_000.0;
        }

        private void reset()
        {
            count =
                    0L;

            totalNanos =
                    0L;

            maxNanos =
                    0L;
        }
    }
}