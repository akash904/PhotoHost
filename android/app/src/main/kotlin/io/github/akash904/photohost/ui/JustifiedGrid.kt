package io.github.akash904.photohost.ui

import io.github.akash904.photohost.net.TimelineItemDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One row of the rendered timeline: either a sticky date header or a justified row of photos. */
sealed interface GridEntry {
    data class DayHeader(val dayKey: String, val label: String, val count: Int) : GridEntry
    data class PhotoRow(val cells: List<Cell>, val height: Int) : GridEntry
}

data class Cell(val item: TimelineItemDto, val width: Int, val height: Int)

/**
 * Packs a timeline into justified rows, grouped by the day each photo was taken.
 *
 * Aspect ratios accumulate until the row would overflow at the target height, then the height is
 * solved so the row fills the width exactly. Every photo keeps its true shape -- a uniform square
 * grid would crop each one and turn a library into a spreadsheet.
 *
 * Days are computed in the photo's OWN timezone using the offset captured at index time, not the
 * viewer's. Otherwise a holiday abroad silently re-dates itself when you get home.
 */
object JustifiedGrid {

    fun build(
        items: List<TimelineItemDto>,
        containerWidth: Int,
        targetHeight: Int,
        gap: Int,
    ): List<GridEntry> {
        if (containerWidth <= 0 || items.isEmpty()) return emptyList()
        val out = ArrayList<GridEntry>()

        var dayKey: String? = null
        var dayItems = ArrayList<TimelineItemDto>()

        fun flushDay() {
            val key = dayKey ?: return
            if (dayItems.isEmpty()) return
            out += GridEntry.DayHeader(key, labelFor(dayItems.first()), dayItems.size)
            out += rowsFor(dayItems, containerWidth, targetHeight, gap)
            dayItems = ArrayList()
        }

        for (item in items) {
            val key = dayKeyOf(item)
            if (key != dayKey) {
                flushDay()
                dayKey = key
            }
            dayItems.add(item)
        }
        flushDay()
        return out
    }

    private fun rowsFor(
        items: List<TimelineItemDto>,
        containerWidth: Int,
        targetHeight: Int,
        gap: Int,
    ): List<GridEntry.PhotoRow> {
        val rows = ArrayList<GridEntry.PhotoRow>()
        var current = ArrayList<TimelineItemDto>()
        var ratioSum = 0f

        fun flush(isLast: Boolean) {
            if (current.isEmpty()) return
            val avail = containerWidth - gap * (current.size - 1)
            val justified = avail / ratioSum

            // A full row always fills the width. The trailing row of a day needs judgement:
            // stretching one lone photo across the screen looks absurd, but leaving a nearly-full
            // row short makes the grid look broken. Justify from 40% full, capped so a single wide
            // photo cannot balloon.
            val height = if (!isLast) {
                justified
            } else {
                val fill = (ratioSum * targetHeight) / avail
                if (fill >= 0.4f) minOf(justified, targetHeight * 1.7f) else targetHeight.toFloat()
            }

            rows += GridEntry.PhotoRow(
                cells = current.map { Cell(it, (it.aspectRatio * height).toInt(), height.toInt()) },
                height = height.toInt(),
            )
            current = ArrayList()
            ratioSum = 0f
        }

        for (item in items) {
            current.add(item)
            ratioSum += item.aspectRatio
            if (ratioSum * targetHeight + gap * (current.size - 1) >= containerWidth) flush(false)
        }
        flush(true)
        return rows
    }

    /** Shifts by the stored offset and formats in UTC, which renders the photo's own wall clock. */
    private fun shifted(item: TimelineItemDto) =
        Date(item.capturedAt + (item.tzOffsetMinutes ?: 0) * 60_000L)

    fun dayKeyOf(item: TimelineItemDto): String = KEY_FMT.get()!!.format(shifted(item))

    fun labelFor(item: TimelineItemDto): String = LABEL_FMT.get()!!.format(shifted(item))

    fun timeOf(item: TimelineItemDto): String = TIME_FMT.get()!!.format(shifted(item))

    fun durationLabel(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    // SimpleDateFormat is not thread-safe and these are touched from layout on any dispatcher.
    private val KEY_FMT = utc("yyyy-MM-dd")
    private val LABEL_FMT = utc("EEE, d MMM yyyy")
    private val TIME_FMT = utc("HH:mm")

    private fun utc(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
