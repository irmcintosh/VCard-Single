package com.mackseoul.vcardnative.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetBreakpoint
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetColorPair
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetColors
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetLayoutPolicy
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetPhotoPolicy
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetProjection
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetSize
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetState
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetTextPolicy
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetUpdateSequence
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetClickIdentity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QuickCardWidgetProvider : AppWidgetProvider() {
    companion object {
        fun requestRefresh(context: Context) {
            val application = context.applicationContext
            application.sendBroadcast(Intent(application, QuickCardWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            })
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED,
            AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> QuickCardWidgetUpdater.request(context.applicationContext, goAsync())
            else -> super.onReceive(context, intent)
        }
    }
}

internal object QuickCardWidgetUpdater {
    private val worker = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val sequence = QuickCardWidgetUpdateSequence()
    private val queueLock = Any()
    private var queued: Request? = null
    private var running = false

    private class Request(val context: Context, val ticket: Long, val pending: BroadcastReceiver.PendingResult) {
        val finished = AtomicBoolean(false)
        var deadline: ScheduledFuture<*>? = null
        fun finish() {
            if (finished.compareAndSet(false, true)) {
                deadline?.cancel(false)
                pending.finish()
            }
        }
    }

    @Synchronized
    fun request(context: Context, pending: BroadcastReceiver.PendingResult) {
        val request = Request(context, sequence.next(), pending)
        try {
            request.deadline = deadlines.schedule({ request.finish() }, 8, TimeUnit.SECONDS)
            synchronized(queueLock) {
                queued?.finish()
                queued = request
                if (!running) {
                    running = true
                    worker.execute(::drain)
                }
            }
        } catch (_: Exception) {
            synchronized(queueLock) {
                if (queued === request) queued = null
                running = false
            }
            request.finish()
        }
    }

    private fun drain() {
        while (true) {
            val request = synchronized(queueLock) {
                val next = queued
                queued = null
                if (next == null) running = false
                next
            } ?: return
            try {
                if (!request.finished.get()) update(request)
            } catch (_: Exception) {
                request.finish()
            } finally {
                request.finish()
            }
        }
    }

    private fun update(request: Request) {
        val context = request.context
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, QuickCardWidgetProvider::class.java)
        if (manager.getAppWidgetIds(component).isEmpty()) return
        var photo: Bitmap? = null
        val state = try {
            val repository = ProfileRepository.managed(AndroidStoreFiles(context.noBackupFilesDir))
            val read = repository.load()
            val projected = QuickCardWidgetProjection.from(read)
            if (projected is QuickCardWidgetState.Ready && projected.photo != null && read is ProfileRead.Ready) {
                val asset = projected.photo
                val bytes = repository.withSavedSnapshot(read.saved.token) { repository.readImage(asset) }
                if (!request.finished.get()) photo = decodePhoto(bytes, asset.width, asset.height, context)
            }
            projected
        } catch (_: Exception) {
            QuickCardWidgetState.Unavailable(context.getString(R.string.quick_card_widget_unavailable), null, null)
        }
        try {
            val palette = QuickCardWidgetPalette.resolve(context,
                (state as? QuickCardWidgetState.Ready)?.accent ?: Appearance().accent)
            sequence.publishWidgetsIfCurrent(request.ticket, manager.getAppWidgetIds(component),
                isActive = { widgetId ->
                    !request.finished.get() && manager.getAppWidgetInfo(widgetId)?.provider == component
                }) { widgetId ->
                    val layouts = QuickCardWidgetLayoutPolicy.breakpoints.associate { breakpoint ->
                        SizeF(breakpoint.widthDp, breakpoint.heightDp) to
                            remoteViews(context, widgetId, breakpoint, state, photo, palette)
                    }
                    manager.updateAppWidget(widgetId, RemoteViews(layouts))
            }
        } catch (_: Exception) {
        } finally {
            photo?.recycle()
        }
    }

    private fun decodePhoto(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int, context: Context): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == expectedWidth && bounds.outHeight == expectedHeight)
        require(expectedWidth in 1..1024 && expectedHeight in 1..1024)
        val edge = (QuickCardWidgetSize.Expanded.photoEdgeDp * context.resources.displayMetrics.density)
            .toInt().coerceIn(1, QuickCardWidgetPhotoPolicy.MAX_EDGE_PIXELS)
        val options = BitmapFactory.Options().apply {
            inSampleSize = QuickCardWidgetPhotoPolicy.sampleSize(expectedWidth, expectedHeight, edge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Verified photo could not be decoded.")
        try {
            val outputEdge = minOf(edge, decoded.width, decoded.height)
            val output = Bitmap.createBitmap(outputEdge, outputEdge, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val target = RectF(0f, 0f, outputEdge.toFloat(), outputEdge.toFloat())
            val clip = Path().apply { addOval(target, Path.Direction.CW) }
            canvas.clipPath(clip)
            val cropEdge = minOf(decoded.width, decoded.height)
            val left = (decoded.width - cropEdge) / 2
            val top = (decoded.height - cropEdge) / 2
            canvas.drawBitmap(decoded, Rect(left, top, left + cropEdge, top + cropEdge), target,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } finally {
            decoded.recycle()
        }
    }

    private fun remoteViews(
        context: Context, widgetId: Int, breakpoint: QuickCardWidgetBreakpoint, state: QuickCardWidgetState,
        photo: Bitmap?, palette: QuickCardWidgetPalette,
    ): RemoteViews {
        val size = breakpoint.size
        val layout = when (size) {
            QuickCardWidgetSize.VeryNarrow, QuickCardWidgetSize.Compact -> R.layout.quick_card_widget_compact
            QuickCardWidgetSize.Standard -> R.layout.quick_card_widget_standard
            QuickCardWidgetSize.Expanded -> R.layout.quick_card_widget_expanded
        }
        val views = RemoteViews(context.packageName, layout)
        val name: String
        val title: String?
        val initials: String?
        when (state) {
            is QuickCardWidgetState.Ready -> {
                name = state.identityText(size)
                title = state.professionalTitle
                initials = state.initials
            }
            QuickCardWidgetState.Empty -> {
                name = context.getString(R.string.quick_card_widget_create)
                title = null
                initials = null
            }
            is QuickCardWidgetState.Unavailable -> {
                name = context.getString(R.string.quick_card_widget_unavailable)
                title = context.getString(R.string.quick_card_widget_recovery)
                initials = null
            }
        }
        val hasPhoto = state is QuickCardWidgetState.Ready && photo != null
        val nameText = if (state is QuickCardWidgetState.Ready) state.visibleIdentityText(size, hasPhoto) else name
        val limits = QuickCardWidgetTextPolicy.limits(breakpoint, context.resources.configuration.fontScale)
        val displayedTitle = if (breakpoint.showsTitleInitials && state is QuickCardWidgetState.Ready) {
            title?.let { QuickCardWidgetProjection.titleInitials(it) ?: it }
        } else title
        if (breakpoint.reservesMenuRow) {
            val metrics = context.resources.displayMetrics
            fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
            views.setViewPadding(R.id.quick_card_body, px(12f), px(QuickCardWidgetLayoutPolicy.MENU_ROW_TOP_INSET_DP),
                px(12f), px(8f))
        }
        views.setViewLayoutWidth(R.id.quick_card_avatar, size.photoEdgeDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.quick_card_avatar, size.photoEdgeDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        views.setTextViewText(R.id.quick_card_name, nameText.orEmpty())
        views.setViewVisibility(R.id.quick_card_name, if (nameText.isNullOrBlank()) View.GONE else View.VISIBLE)
        views.setInt(R.id.quick_card_name, "setMaxLines", limits.nameLines)
        views.setTextViewText(R.id.quick_card_title, displayedTitle.orEmpty())
        views.setInt(R.id.quick_card_title, "setMaxLines", limits.titleLines)
        views.setViewVisibility(R.id.quick_card_title,
            if (displayedTitle.isNullOrBlank() || !limits.showTitle) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.quick_card_initials, initials.orEmpty())
        views.setViewVisibility(R.id.quick_card_photo, if (hasPhoto) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.quick_card_initials, if (!hasPhoto && initials != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.quick_card_person, if (!hasPhoto && initials == null) View.VISIBLE else View.GONE)
        if (hasPhoto) views.setImageViewBitmap(R.id.quick_card_photo, photo)
        palette.applyTo(views)
        val accessibleName = if (state is QuickCardWidgetState.Ready) state.displayName else name
        views.setContentDescription(R.id.quick_card_root, listOfNotNull(accessibleName, title).joinToString(", "))
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_MY_CARD_FROM_WIDGET
            data = Uri.parse(QuickCardWidgetClickIdentity.body(widgetId))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        views.setOnClickPendingIntent(R.id.quick_card_root, PendingIntent.getActivity(context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val menu = Intent(context, QuickCardWidgetMenuActivity::class.java).apply {
            data = Uri.parse(QuickCardWidgetClickIdentity.menu(widgetId))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        views.setOnClickPendingIntent(R.id.quick_card_menu, PendingIntent.getActivity(context, widgetId, menu,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return views
    }
}
/**
 * Light/dark color pairs applied with RemoteViews.setColorInt (API 31+), so the launcher switches contrast with the
 * system night mode without waiting for a refresh. Neutral roles come from the widget color resources; only the
 * committed saved accent tints initials, the generic person icon and the overflow icon.
 */
internal data class QuickCardWidgetPalette(
    val text: QuickCardWidgetColorPair,
    val secondary: QuickCardWidgetColorPair,
    val accent: QuickCardWidgetColorPair,
) {
    fun applyTo(views: RemoteViews) {
        views.setColorInt(R.id.quick_card_name, "setTextColor", text.light, text.dark)
        views.setColorInt(R.id.quick_card_title, "setTextColor", secondary.light, secondary.dark)
        views.setColorInt(R.id.quick_card_initials, "setTextColor", accent.light, accent.dark)
        views.setColorInt(R.id.quick_card_person, "setColorFilter", accent.light, accent.dark)
        views.setColorInt(R.id.quick_card_menu, "setColorFilter", accent.light, accent.dark)
    }

    companion object {
        fun resolve(context: Context, accent: Accent): QuickCardWidgetPalette {
            val light = nightContext(context, Configuration.UI_MODE_NIGHT_NO)
            val dark = nightContext(context, Configuration.UI_MODE_NIGHT_YES)
            fun pair(id: Int) = QuickCardWidgetColorPair(light.getColor(id), dark.getColor(id))
            return QuickCardWidgetPalette(
                text = pair(R.color.quick_card_text),
                secondary = pair(R.color.quick_card_secondary),
                accent = QuickCardWidgetColors.accent(accent),
            )
        }

        private fun nightContext(context: Context, night: Int): Context {
            val configuration = Configuration(context.resources.configuration)
            configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            return context.createConfigurationContext(configuration)
        }
    }
}
