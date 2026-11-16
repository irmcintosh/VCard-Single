package com.mackseoul.vcardnative.ui.widget

data class QuickCardWidgetBreakpoint(
    val widthDp: Float,
    val heightDp: Float,
    val size: QuickCardWidgetSize,
) {
    /** Narrow Standard entries push the avatar below the top-right menu so the two cannot overlap. */
    val reservesMenuRow: Boolean
        get() = size == QuickCardWidgetSize.Standard &&
            widthDp < QuickCardWidgetLayoutPolicy.STANDARD_MENU_CLEAR_WIDTH_DP

    /** Single-row entries narrower than the full-title Compact entry (a launcher 2x1) show title initials. */
    val showsTitleInitials: Boolean
        get() = size == QuickCardWidgetSize.VeryNarrow ||
            (size == QuickCardWidgetSize.Compact && widthDp < QuickCardWidgetLayoutPolicy.COMPACT_FULL_TITLE_MIN_WIDTH_DP)
}

/**
 * Each entry is the minimum widget size at which its presentation applies; extra entries of one class only change the
 * height-derived line limits, so the three XML layouts and the four size classes are unchanged.
 */
object QuickCardWidgetLayoutPolicy {
    /** A centered 56 dp avatar reaches the 48 dp menu column when the width is below 2 x (28 + 48) dp. */
    const val STANDARD_MENU_CLEAR_WIDTH_DP = 152f
    const val MENU_ROW_TOP_INSET_DP = 36f

    /**
     * Owner-observed 2x1 reports a Compact-sized width, so this sits between that and a launcher 3x1. The dp value is
     * an estimate, not a measurement: launchers differ in how many dp a cell spans.
     */
    const val COMPACT_FULL_TITLE_MIN_WIDTH_DP = 240f

    val breakpoints = listOf(
        QuickCardWidgetBreakpoint(120f, 48f, QuickCardWidgetSize.VeryNarrow),
        QuickCardWidgetBreakpoint(180f, 48f, QuickCardWidgetSize.Compact),
        QuickCardWidgetBreakpoint(COMPACT_FULL_TITLE_MIN_WIDTH_DP, 48f, QuickCardWidgetSize.Compact),
        QuickCardWidgetBreakpoint(120f, 152f, QuickCardWidgetSize.Standard),
        QuickCardWidgetBreakpoint(120f, 184f, QuickCardWidgetSize.Standard),
        QuickCardWidgetBreakpoint(152f, 120f, QuickCardWidgetSize.Standard),
        QuickCardWidgetBreakpoint(152f, 136f, QuickCardWidgetSize.Standard),
        QuickCardWidgetBreakpoint(152f, 160f, QuickCardWidgetSize.Standard),
        QuickCardWidgetBreakpoint(260f, 120f, QuickCardWidgetSize.Expanded),
        QuickCardWidgetBreakpoint(260f, 160f, QuickCardWidgetSize.Expanded),
    )

    /** Mirrors the host's expected choice: the fitting entry with the largest area, else the smallest entry. */
    fun entryFor(widthDp: Float, heightDp: Float): QuickCardWidgetBreakpoint =
        breakpoints.filter { widthDp >= it.widthDp && heightDp >= it.heightDp }
            .maxByOrNull { it.widthDp * it.heightDp }
            ?: breakpoints.minBy { it.widthDp * it.heightDp }

    fun select(widthDp: Float, heightDp: Float): QuickCardWidgetSize = entryFor(widthDp, heightDp).size
}

data class QuickCardWidgetTextLimits(val nameLines: Int, val titleLines: Int, val showTitle: Boolean)

/**
 * Owner-approved adaptive text: Standard/Expanded drop to one name and one title line at font scale >= 1.3; the 48 dp
 * Compact/VeryNarrow layout hides the title at >= 1.5 so the name is never clipped. Never zero lines.
 * The breakpoint overload instead sizes title lines to the entry's minimum height and the scaled line heights.
 */
object QuickCardWidgetTextPolicy {
    const val REDUCED_LINES_FONT_SCALE = 1.3f
    const val HIDE_COMPACT_TITLE_FONT_SCALE = 1.5f
    private const val LINE_HEIGHT_RATIO = 1.2f

    fun limits(size: QuickCardWidgetSize, fontScale: Float): QuickCardWidgetTextLimits = when (size) {
        QuickCardWidgetSize.VeryNarrow, QuickCardWidgetSize.Compact -> QuickCardWidgetTextLimits(
            size.maxNameLines, size.maxTitleLines, showTitle = !(fontScale >= HIDE_COMPACT_TITLE_FONT_SCALE),
        )
        QuickCardWidgetSize.Standard, QuickCardWidgetSize.Expanded ->
            if (fontScale >= REDUCED_LINES_FONT_SCALE) QuickCardWidgetTextLimits(1, 1, showTitle = true)
            else QuickCardWidgetTextLimits(size.maxNameLines, size.maxTitleLines, showTitle = true)
    }

    fun limits(breakpoint: QuickCardWidgetBreakpoint, fontScale: Float): QuickCardWidgetTextLimits {
        val classLimits = limits(breakpoint.size, fontScale)
        // Fixed vertical space in dp (padding, avatar, gaps) and the text sizes in the matching XML layout.
        val nameSp: Float
        val titleSp: Float
        val fixedDp: Float
        val maxTitleLines: Int
        when (breakpoint.size) {
            QuickCardWidgetSize.Standard -> {
                nameSp = 16f
                titleSp = 12f
                fixedDp = (if (breakpoint.reservesMenuRow) QuickCardWidgetLayoutPolicy.MENU_ROW_TOP_INSET_DP else 8f) +
                    8f + 56f + 8f + 4f
                maxTitleLines = 3
            }
            QuickCardWidgetSize.Expanded -> {
                nameSp = 22f
                titleSp = 16f
                fixedDp = 24f + 4f
                maxTitleLines = 4
            }
            else -> return classLimits
        }
        val scale = fontScale.coerceAtLeast(1f)
        val nameLineDp = nameSp * LINE_HEIGHT_RATIO * scale
        val titleLineDp = titleSp * LINE_HEIGHT_RATIO * scale
        val usable = breakpoint.heightDp - fixedDp
        for (nameLines in classLimits.nameLines downTo 1) {
            val titleLines = ((usable - nameLines * nameLineDp) / titleLineDp).toInt()
            if (titleLines >= 1) {
                return QuickCardWidgetTextLimits(nameLines, titleLines.coerceAtMost(maxTitleLines), showTitle = true)
            }
        }
        return QuickCardWidgetTextLimits(1, 1, showTitle = true)
    }
}

object QuickCardWidgetPhotoPolicy {
    const val MAX_EDGE_PIXELS = 256

    fun sampleSize(width: Int, height: Int, maximumEdge: Int = MAX_EDGE_PIXELS): Int {
        require(width in 1..1024 && height in 1..1024 && maximumEdge in 1..MAX_EDGE_PIXELS)
        var sample = 1
        while (width.toLong() > maximumEdge.toLong() * sample ||
            height.toLong() > maximumEdge.toLong() * sample) {
            sample *= 2
        }
        return sample
    }
}

class QuickCardWidgetUpdateSequence {
    private var sequence = 0L

    @Synchronized
    fun next(): Long {
        sequence = Math.addExact(sequence, 1L)
        return sequence
    }

    @Synchronized
    fun publishIfCurrent(ticket: Long, publish: () -> Unit): Boolean {
        if (ticket != sequence) return false
        publish()
        return true
    }

    fun publishWidgetsIfCurrent(
        ticket: Long,
        widgetIds: IntArray,
        isActive: (Int) -> Boolean,
        publish: (Int) -> Unit,
    ): Boolean = publishIfCurrent(ticket) {
        widgetIds.distinct().forEach { id ->
            if (isActive(id)) publish(id)
        }
    }
}