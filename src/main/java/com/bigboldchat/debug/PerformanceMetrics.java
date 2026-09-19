package com.bigboldchat.debug;

import java.util.Locale;

import net.runelite.api.events.CommandExecuted;

import lombok.extern.slf4j.Slf4j;

/**
 * Collects Chat XL performance metrics.
 */
@Slf4j
public final class PerformanceMetrics
{
    private static final String COMMAND = "chatxl-perf";
    private static final long REPORT_INTERVAL_NANOS = 10_000_000_000L;

    private static final int GAME_BODY_SCRIPT = 199;
    private static final int CHAT_BODY_SCRIPT = 203;
    private static final int CHANNEL_BODY_SCRIPT = 4483;

    /*
     * TIMINGS
     */

    // Measure Script PRE processing time.
    private final TimingMetric pre199 = new TimingMetric();

    private final TimingMetric pre203 = new TimingMetric();

    private final TimingMetric pre4483 = new TimingMetric();

    // Measure Script POST processing time.
    private final TimingMetric post199 = new TimingMetric();

    private final TimingMetric post203 = new TimingMetric();

    private final TimingMetric post4483 = new TimingMetric();

    // Measure construction measurement time.
    private final TimingMetric measurement = new TimingMetric();

    // Measure semantic text normalization time.
    private final TimingMetric normalization = new TimingMetric();

    // Measure uncached FontTypeFace resolution time.
    private final TimingMetric fontResolution = new TimingMetric();

    // Measure chatbox geometry application time.
    private final TimingMetric resizeApply = new TimingMetric();

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

    // Count persistent row-index full builds, local repairs, and direct reuses.
    private long rowIndexBuilds;

    private long rowIndexRepairs;

    private long rowIndexReuses;

    // Count correlation fallback requests.
    private long fallbackSearches;

    // Count actual lazy fallback index builds.
    private long fallbackBuilds;

    // Count fallback requests served by an already-built per-POST index.
    private long fallbackReuses;

    // Count rank-icon searches, full-tree fallbacks, and examined nodes.
    private long rankSearches;

    private long rankFallbacks;

    private long rankNodesExamined;

    /*
     * Classify strict row-first rank misses before shallow recovery.
     */
    private long rankRowNoSprite;

    private long rankRowXMismatch;

    private long rankRowYMismatch;

    private long rankShallowRecoveries;

    /*
     * Classify recursive fallback results.
     */
    private long rankFallbackHits;

    private long rankFallbackMisses;

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

    // Count chatbox-resize geometry activity.
    private long resizeApplies;

    private long resizeNoops;

    private long resizeWidthChanges;

    private long resizeHeightChanges;

    private long resizeMissingWidgets;

    private long resizeRestores;

    private long reportStartedAt;
    private boolean enabled;

    public boolean isEnabled()
    {
        return enabled;
    }

    public boolean onCommandExecuted(CommandExecuted event)
    {
        if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand()))
        {
            return false;
        }

        if (enabled)
        {
            reportNow();
            enabled = false;
        } else {
            enabled = true;
            resetWindow(System.nanoTime());
        }

        log.debug(
                "[Chat XL][Performance] {}", enabled
                        ? "ARMED"
                        : "DISARMED");

        return true;
    }

    /*
     * RECORDING
     */

    public void recordPre(int scriptId, long elapsedNanos)
    {
        if (!enabled)
        {
            return;
        }

        final TimingMetric metric = preMetric(scriptId);

        if (metric != null)
        {
            metric.record(elapsedNanos);
        }
    }

    public void recordPost(int scriptId, long elapsedNanos)
    {
        if (!enabled)
        {
            return;
        }

        final TimingMetric metric = postMetric(scriptId);

        if (metric != null)
        {
            metric.record(elapsedNanos);
        }
    }

    public void recordMeasurement(long elapsedNanos)
    {
        if (!enabled)
        {
            return;
        }

        measurement.record(elapsedNanos);
    }

    public void recordNormalization(long elapsedNanos)
    {
        if (!enabled)
        {
            return;
        }

        normalization.record(elapsedNanos);
    }

    public void recordFontCacheHit()
    {
        if (!enabled)
        {
            return;
        }

        fontCacheHits++;
    }

    public void recordFontCacheMiss(long elapsedNanos)
    {
        if (!enabled)
        {
            return;
        }

        fontCacheMisses++;

        fontResolution.record(elapsedNanos);
    }

    public void recordWidgetsExamined(int count)
    {
        if (!enabled)
        {
            return;
        }

        if (count > 0)
        {
            widgetsExamined += count;
        }
    }

    public void recordSurfaceSearch()
    {
        if (!enabled)
        {
            return;
        }

        surfaceSearches++;
    }

    public void recordRowSearches(int candidates)
    {
        if (!enabled)
        {
            return;
        }

        rowSearches++;

        if (candidates > 0)
        {
            rowCandidates += candidates;
        }
    }

    public void recordRowIndexBuild()
    {
        if (!enabled)
        {
            return;
        }

        rowIndexBuilds++;
    }

    public void recordRowIndexRepair()
    {
        if (!enabled)
        {
            return;
        }

        rowIndexRepairs++;
    }

    public void recordRowIndexReuse()
    {
        if (!enabled)
        {
            return;
        }

        rowIndexReuses++;
    }

    public void recordFallbackSearch()
    {
        if (!enabled)
        {
            return;
        }

        fallbackSearches++;
    }

    public void recordFallbackBuild()
    {
        if (!enabled)
        {
            return;
        }

        fallbackBuilds++;
    }

    public void recordFallbackReuse()
    {
        if (!enabled)
        {
            return;
        }

        fallbackReuses++;
    }

    public void recordRankSearch()
    {
        if (!enabled)
        {
            return;
        }

        rankSearches++;
    }

    public void recordRankFallback()
    {
        if (!enabled)
        {
            return;
        }

        rankFallbacks++;
    }

    public void recordRankRowNoSprite()
    {
        if (!enabled)
        {
            return;
        }

        rankRowNoSprite++;
    }

    public void recordRankRowXMismatch()
    {
        if (!enabled)
        {
            return;
        }

        rankRowXMismatch++;
    }

    public void recordRankRowYMismatch()
    {
        if (!enabled)
        {
            return;
        }

        rankRowYMismatch++;
    }

    public void recordRankShallowRecovery()
    {
        if (!enabled)
        {
            return;
        }

        rankShallowRecoveries++;
    }

    public void recordRankFallbackHit()
    {
        if (!enabled)
        {
            return;
        }

        rankFallbackHits++;
    }

    public void recordRankFallbackMiss()
    {
        if (!enabled)
        {
            return;
        }

        rankFallbackMisses++;
    }

    public void recordRankNodesExamined(int count)
    {
        if (!enabled)
        {
            return;
        }

        if (count > 0)
        {
            rankNodesExamined += count;
        }
    }

    public void recordWidgetMutation()
    {
        if (!enabled)
        {
            return;
        }

        widgetMutations++;
    }

    public void recordRevalidate()
    {
        if (!enabled)
        {
            return;
        }

        revalidates++;
    }

    public void recordResizeApply(long elapsedNanos, boolean widthChanged, boolean heightChanged)
    {
        if (!enabled)
        {
            return;
        }

        resizeApply.record(elapsedNanos);

        resizeApplies++;

        if (!widthChanged && !heightChanged)
        {
            resizeNoops++;
        }

        if (widthChanged)
        {
            resizeWidthChanges++;
        }

        if (heightChanged)
        {
            resizeHeightChanges++;
        }
    }

    public void recordResizeMissingWidgets()
    {
        if (!enabled)
        {
            return;
        }

        resizeMissingWidgets++;
    }

    public void recordResizeRestore()
    {
        if (!enabled)
        {
            return;
        }

        resizeRestores++;
    }

    public void recordRefreshChat(RefreshReason reason)
    {
        if (!enabled)
        {
            return;
        }

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
        if (!enabled)
        {
            return;
        }

        final long now = System.nanoTime();

        if (now - reportStartedAt < REPORT_INTERVAL_NANOS)
        {
            return;
        }

        report(now);
    }

    public void reportNow()
    {
        if (!enabled)
        {
            return;
        }

        report(System.nanoTime());
    }

    private void report(long now)
    {
        final long elapsedNanos = now - reportStartedAt;

        if (elapsedNanos <= 0L)
        {
            return;
        }

        final double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

        log.debug("[Chat XL][Performance] Window={}", String.format(
                Locale.ROOT,
                "%.3fs",
                elapsedSeconds));

        log.debug(
                "[Chat XL][Performance] PRE"
                        + " | 199={}"
                        + " | 203={}"
                        + " | 4483={}",
                formatTiming(pre199),
                formatTiming(pre203),
                formatTiming(pre4483));

        log.debug(
                "[Chat XL][Performance] POST"
                        + " | 199={}"
                        + " | 203={}"
                        + " | 4483={}",
                formatTiming(post199),
                formatTiming(post203),
                formatTiming(post4483));

        log.debug(
                "[Chat XL][Performance] SERVICES"
                        + " | Measurement={}"
                        + " | Normalize={}"
                        + " | FontResolve={}"
                        + " | FontCacheHits={}"
                        + " | FontCacheMisses={}",
                formatTiming(measurement),
                formatTiming(normalization),
                formatTiming(fontResolution),
                fontCacheHits,
                fontCacheMisses);

        log.debug(
                "[Chat XL][Performance] CORRELATION"
                        + " | Widgets={}"
                        + " | RowSearches={}"
                        + " | RowCandidates={}"
                        + " | RowIndexBuilds={}"
                        + " | RowIndexRepairs={}"
                        + " | RowIndexReuses={}"
                        + " | SurfaceSearches={}"
                        + " | FallbackSearches={}"
                        + " | FallbackBuilds={}"
                        + " | FallbackReuses={}"
                        + " | RankSearches={}"
                        + " | RankFallbacks={}"
                        + " | RankNoSprite={}"
                        + " | RankXMismatch={}"
                        + " | RankYMismatch={}"
                        + " | RankShallowRecoveries={}"
                        + " | RankFallbackHits={}"
                        + " | RankFallbackMisses={}"
                        + " | RankNodes={}",
                widgetsExamined,
                rowSearches,
                rowCandidates,
                rowIndexBuilds,
                rowIndexRepairs,
                rowIndexReuses,
                surfaceSearches,
                fallbackSearches,
                fallbackBuilds,
                fallbackReuses,
                rankSearches,
                rankFallbacks,
                rankRowNoSprite,
                rankRowXMismatch,
                rankRowYMismatch,
                rankShallowRecoveries,
                rankFallbackHits,
                rankFallbackMisses,
                rankNodesExamined);

        log.debug(
                "[Chat XL][Performance] PRESENTATION"
                        + " | Mutations={}"
                        + " | Revalidates={}",
                widgetMutations,
                revalidates);

        log.debug(
                "[Chat XL][Performance] RESIZE"
                        + " | Apply={}"
                        + " | Applies={}"
                        + " | Noops={}"
                        + " | WidthChanges={}"
                        + " | HeightChanges={}"
                        + " | MissingWidgets={}"
                        + " | Restores={}",
                formatTiming(resizeApply),
                resizeApplies,
                resizeNoops,
                resizeWidthChanges,
                resizeHeightChanges,
                resizeMissingWidgets,
                resizeRestores);

        log.debug(
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

        resetWindow(now);
    }

    /*
     * HELPERS
     */

    private TimingMetric preMetric(int scriptId)
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

    private TimingMetric postMetric(int scriptId)
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

    private static String formatTiming(TimingMetric metric)
    {
        if (metric == null || metric.count == 0L)
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

    private void resetWindow(long now)
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
        resizeApply.reset();

        fontCacheHits = 0L;
        fontCacheMisses = 0L;
        widgetsExamined = 0L;
        rowSearches = 0L;
        rowCandidates = 0L;
        rowIndexBuilds = 0L;
        rowIndexRepairs = 0L;
        rowIndexReuses = 0L;
        surfaceSearches = 0L;
        fallbackSearches = 0L;
        fallbackBuilds = 0L;
        fallbackReuses = 0L;
        rankSearches = 0L;
        rankFallbacks = 0L;
        rankRowNoSprite = 0L;
        rankRowXMismatch = 0L;
        rankRowYMismatch = 0L;
        rankShallowRecoveries = 0L;
        rankFallbackHits = 0L;
        rankFallbackMisses = 0L;
        rankNodesExamined = 0L;
        widgetMutations = 0L;
        revalidates = 0L;
        refreshChatCalls = 0L;
        refreshStartup = 0L;
        refreshFontChanged = 0L;
        refreshShutdown = 0L;
        refreshWidthChanged = 0L;
        refreshOther = 0L;
        resizeApplies = 0L;
        resizeNoops = 0L;
        resizeWidthChanges = 0L;
        resizeHeightChanges = 0L;
        resizeMissingWidgets = 0L;
        resizeRestores = 0L;
        reportStartedAt = now;
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

        private void record(long elapsedNanos)
        {
            if (elapsedNanos < 0L)
            {
                return;
            }

            count++;
            totalNanos += elapsedNanos;

            if (elapsedNanos > maxNanos)
            {
                maxNanos = elapsedNanos;
            }
        }

        private double averageMilliseconds()
        {
            if (count == 0L)
            {
                return 0.0;
            }

            return totalNanos / (double) count / 1_000_000.0;
        }

        private double maxMilliseconds()
        {
            return maxNanos / 1_000_000.0;
        }

        private void reset()
        {
            count = 0L;
            totalNanos = 0L;
            maxNanos = 0L;
        }
    }
}
