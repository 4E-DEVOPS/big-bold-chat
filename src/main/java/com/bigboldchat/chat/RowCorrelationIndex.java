package com.bigboldchat.chat;

import com.bigboldchat.chat.FontLayoutService.Surface;
import com.bigboldchat.debug.PerformanceMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Persistent shallow row index for one chat surface.
 *
 * Indexes the root and immediate dynamic/static/nested children by RowKey,
 * supports local repair, and watches Chat XL-adjusted widgets for row changes.
 */
final class RowCorrelationIndex
{
    private final Client client;
    private final Surface surface;
    private final PerformanceMetrics performanceMetrics;

    private Widget indexedRoot;

    private Map<RowKey, List<Widget>> widgetsByRow;
    private IdentityHashMap<Widget, RowKey> rowByWidget;

    private final IdentityHashMap<Widget, WatchedRowMove> watchedMoves = new IdentityHashMap<>();
    private final Map<RowKey, List<Widget>> watchedWidgetsByPreviousRow = new HashMap<>();

    RowCorrelationIndex(Client client, Surface surface, PerformanceMetrics performanceMetrics)
    {
        this.client = client;
        this.surface = surface;
        this.performanceMetrics = performanceMetrics;
    }

    List<Widget> findRow(Widget lineWidget)
    {
        if (lineWidget == null || surface == null)
        {
            return Collections.emptyList();
        }

        final Widget root = surface == Surface.SPLIT_PRIVATE
                ? client.getWidget(InterfaceID.PM_CHAT, 0)
                : client.getWidget(InterfaceID.Chatbox.SCROLLAREA);

        if (root == null)
        {
            clear();
            return Collections.emptyList();
        }

        final RowKey targetRow = RowKey.of(lineWidget);

        boolean rebuilt = false;
        boolean repaired = false;

        if (widgetsByRow == null || rowByWidget == null || indexedRoot != root)
        {
            build(root);
            rebuilt = true;
        } else {
            repaired |= repairWatchedWidgetsForRow(targetRow); // Repair watched widgets that returned to this row.

            final RowKey indexedAnchorRow = rowByWidget.get(lineWidget);
            if (indexedAnchorRow == null)
            {
                /*
                 * A new line-widget object appeared after the last index
                 * build. We cannot discover its sibling candidates without
                 * enumerating the surface once.
                 */
                build(root);
                rebuilt = true;
            } else if (!indexedAnchorRow.equals(targetRow)) {
                // Re-key the moved anchor row and its known siblings.
                repaired |= repairRow(indexedAnchorRow);
            }
        }

        if (!rebuilt)
        {
            /*
             * Validate only the requested bucket. Outgoing recycled
             * candidates are moved to their current row without touching
             * unrelated rows or walking the complete surface.
             */
            repaired |= repairRow(targetRow);
        }

        List<Widget> candidates = widgetsByRow.get(targetRow);

        /*
         * The exact line widget must be present in its requested bucket.
         * If not, local knowledge is insufficient and one complete build
         * remains the conservative correctness fallback.
         */
        if (!rebuilt && !containsIdentity(candidates, lineWidget))
        {
            build(root);
            rebuilt = true;
            candidates = widgetsByRow.get(targetRow);
        }

        if (performanceMetrics != null)
        {
            if (rebuilt)
            {
                performanceMetrics.recordRowIndexBuild();
            } else if (repaired) {
                performanceMetrics.recordRowIndexRepair();
            } else {
                performanceMetrics.recordRowIndexReuse();
            }
        }

        return candidates != null
                ? candidates
                : Collections.emptyList();
    }

    private boolean repairRow(RowKey indexedRow)
    {
        if (indexedRow == null || widgetsByRow == null || rowByWidget == null)
        {
            return false;
        }

        final List<Widget> indexedWidgets = widgetsByRow.get(indexedRow);

        if (indexedWidgets == null || indexedWidgets.isEmpty())
        {
            return false;
        }

        final List<Widget> snapshot = new ArrayList<>(indexedWidgets);

        boolean repaired = false;

        for (Widget widget : snapshot)
        {
            if (widget == null)
            {
                continue;
            }

            recordRowWidgetExamined();

            final RowKey currentRow = RowKey.of(widget);
            final RowKey recordedRow = rowByWidget.get(widget);

            if (recordedRow == null)
            {
                /*
                 * This should not occur for a healthy index. Keep the
                 * reverse mapping internally consistent without a scan.
                 */
                rowByWidget.put(widget, currentRow);

                if (!indexedRow.equals(currentRow))
                {
                    removeWidgetFromRow(widget, indexedRow);
                    addWidgetToRow(widget, currentRow);
                    repaired = true;
                }

                continue;
            }

            if (!recordedRow.equals(currentRow))
            {
                moveIndexedWidget(widget, recordedRow, currentRow);
                reconcileWatchedMoveAfterExternalGeometryChange(widget, currentRow);
                repaired = true;
            }
        }

        return repaired;
    }

    private boolean repairWatchedWidgetsForRow(RowKey requestedRow)
    {
        if (requestedRow == null || rowByWidget == null)
        {
            return false;
        }

        final List<Widget> watchedWidgets = watchedWidgetsByPreviousRow.get(requestedRow);

        if (watchedWidgets == null || watchedWidgets.isEmpty())
        {
            return false;
        }

        final List<Widget> snapshot = new ArrayList<>(watchedWidgets);

        boolean repaired = false;

        for (Widget widget : snapshot)
        {
            if (widget == null)
            {
                continue;
            }

            final WatchedRowMove watchedMove = watchedMoves.get(widget);

            if (watchedMove == null || !requestedRow.equals(watchedMove.previousRow))
            {
                removeWatchedMove(widget);
                continue;
            }

            final RowKey recordedRow = rowByWidget.get(widget);

            if (recordedRow == null)
            {
                removeWatchedMove(widget);
                continue;
            }

            recordRowWidgetExamined();

            final RowKey currentRow = RowKey.of(widget);

            if (!recordedRow.equals(currentRow))
            {
                moveIndexedWidget(widget, recordedRow, currentRow);
                repaired = true;
            }

            // Stop watching once the widget leaves the Chat XL-adjusted row.
            if (!watchedMove.adjustedRow.equals(currentRow))
            {
                removeWatchedMove(widget);
            }
        }

        return repaired;
    }

    void observeSurfaceWidget(Widget root, Widget widget)
    {
        if (root == null
                || widget == null
                || indexedRoot != root
                || widgetsByRow == null
                || rowByWidget == null)
        {
            return;
        }

        final RowKey currentRow = RowKey.of(widget);
        final RowKey recordedRow = rowByWidget.get(widget);

        if (recordedRow == null)
        {
            addWidgetToRow(widget, currentRow);
            rowByWidget.put(widget, currentRow);
            return;
        }

        if (!recordedRow.equals(currentRow))
        {
            moveIndexedWidget(widget, recordedRow, currentRow);
            reconcileWatchedMoveAfterExternalGeometryChange(widget, currentRow);
        }
    }

    void onWidgetGeometryChangedByChatXl(Widget widget, int previousOriginalY, int previousRelativeY)
    {
        if (widget == null || rowByWidget == null)
        {
            return;
        }

        final RowKey previousRow = new RowKey(previousOriginalY, previousRelativeY);
        final RowKey recordedRow = rowByWidget.get(widget);

        if (recordedRow == null)
        {
            return;
        }

        final RowKey adjustedRow = RowKey.of(widget);

        if (!recordedRow.equals(adjustedRow))
        {
            moveIndexedWidget(widget, recordedRow, adjustedRow);
        }

        if (!previousRow.equals(adjustedRow))
        {
            watchMove(widget, previousRow, adjustedRow);
        }
    }

    private void moveIndexedWidget(Widget widget, RowKey oldRow, RowKey newRow)
    {
        if (widget == null || newRow == null || rowByWidget == null || widgetsByRow == null)
        {
            return;
        }

        if (oldRow != null)
        {
            removeWidgetFromRow(widget, oldRow);
        }

        addWidgetToRow(widget, newRow);
        rowByWidget.put(widget, newRow);
    }

    private void addWidgetToRow(Widget widget, RowKey row)
    {
        if (widget == null || row == null || widgetsByRow == null)
        {
            return;
        }

        final List<Widget> widgets = widgetsByRow.computeIfAbsent(row, ignored -> new ArrayList<>());

        if (!containsIdentity(widgets, widget))
        {
            widgets.add(widget);
        }
    }

    private void removeWidgetFromRow(Widget widget, RowKey row)
    {
        if (widget == null || row == null || widgetsByRow == null)
        {
            return;
        }

        final List<Widget> widgets = widgetsByRow.get(row);

        if (widgets == null)
        {
            return;
        }

        removeIdentity(widgets, widget);

        if (widgets.isEmpty())
        {
            widgetsByRow.remove(row);
        }
    }

    private void watchMove(Widget widget, RowKey previousRow, RowKey adjustedRow)
    {
        if (widget == null || previousRow == null || adjustedRow == null)
        {
            return;
        }

        removeWatchedMove(widget);
        watchedMoves.put(widget, new WatchedRowMove(previousRow, adjustedRow));

        final List<Widget> watchedWidgets = watchedWidgetsByPreviousRow.computeIfAbsent(previousRow, ignored -> new ArrayList<>());

        if (!containsIdentity(watchedWidgets, widget))
        {
            watchedWidgets.add(widget);
        }
    }

    private void reconcileWatchedMoveAfterExternalGeometryChange(Widget widget, RowKey currentRow)
    {
        final WatchedRowMove watchedMove = watchedMoves.get(widget);

        if (watchedMove == null || currentRow == null)
        {
            return;
        }

        if (!watchedMove.adjustedRow.equals(currentRow))
        {
            removeWatchedMove(widget);
        }
    }

    private void removeWatchedMove(Widget widget)
    {
        if (widget == null)
        {
            return;
        }

        final WatchedRowMove watchedMove = watchedMoves.remove(widget);

        if (watchedMove == null)
        {
            return;
        }

        final List<Widget> watchedWidgets = watchedWidgetsByPreviousRow.get(watchedMove.previousRow);

        if (watchedWidgets == null)
        {
            return;
        }

        removeIdentity(watchedWidgets, widget);

        if (watchedWidgets.isEmpty())
        {
            watchedWidgetsByPreviousRow.remove(watchedMove.previousRow);
        }
    }

    private void build(Widget root)
    {
        indexedRoot = root;
        widgetsByRow = new HashMap<>();
        rowByWidget = new IdentityHashMap<>();

        indexWidget(root);
        indexWidgets(root.getDynamicChildren());
        indexWidgets(root.getStaticChildren());
        indexWidgets(root.getNestedChildren());
        pruneWatchedMovesAfterBuild();
    }

    private void indexWidgets(Widget[] widgets)
    {
        if (widgets == null)
        {
            return;
        }

        for (Widget widget : widgets)
        {
            indexWidget(widget);
        }
    }

    private void indexWidget(Widget widget)
    {
        if (widget == null)
        {
            return;
        }

        recordRowWidgetExamined();

        final RowKey row = RowKey.of(widget);
        addWidgetToRow(widget, row);
        rowByWidget.put(widget, row);
    }

    private void pruneWatchedMovesAfterBuild()
    {
        if (watchedMoves.isEmpty() || rowByWidget == null)
        {
            return;
        }

        final List<Widget> watchedWidgets = new ArrayList<>(watchedMoves.keySet());

        for (Widget widget : watchedWidgets)
        {
            final WatchedRowMove watchedMove = watchedMoves.get(widget);
            final RowKey indexedRow = rowByWidget.get(widget);

            if (watchedMove == null || indexedRow == null || !watchedMove.adjustedRow.equals(indexedRow))
            {
                removeWatchedMove(widget);
            }
        }
    }

    private void clear()
    {
        indexedRoot = null;
        widgetsByRow = null;
        rowByWidget = null;

        watchedMoves.clear();
        watchedWidgetsByPreviousRow.clear();
    }
    private void recordRowWidgetExamined()
    {
        if (performanceMetrics != null)
        {
            performanceMetrics.recordWidgetsExamined(1);
        }
    }

    private boolean containsIdentity(List<Widget> widgets, Widget target)
    {
        if (widgets == null || target == null)
        {
            return false;
        }

        for (Widget widget : widgets)
        {
            if (widget == target)
            {
                return true;
            }
        }

        return false;
    }

    private void removeIdentity(List<Widget> widgets, Widget target)
    {
        if (widgets == null || target == null)
        {
            return;
        }

        for (int i = widgets.size() - 1; i >= 0; i--)
        {
            if (widgets.get(i) == target)
            {
                widgets.remove(i);
            }
        }
    }

    private static final class WatchedRowMove
    {
        private final RowKey previousRow;
        private final RowKey adjustedRow;

        private WatchedRowMove(RowKey previousRow, RowKey adjustedRow)
        {
            this.previousRow = previousRow;
            this.adjustedRow = adjustedRow;
        }
    }

    private static final class RowKey
    {
        private final int originalY;
        private final int relativeY;

        private RowKey(int originalY, int relativeY)
        {
            this.originalY = originalY;
            this.relativeY = relativeY;
        }

        private static RowKey of(Widget widget)
        {
            return new RowKey(widget.getOriginalY(), widget.getRelativeY());
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }

            if (!(other instanceof RowKey))
            {
                return false;
            }

            final RowKey rowKey = (RowKey) other;
            return originalY == rowKey.originalY && relativeY == rowKey.relativeY;
        }

        @Override
        public int hashCode()
        {
            int result = originalY;
            result = 31 * result + relativeY;

            return result;
        }
    }

}
