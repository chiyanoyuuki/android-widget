package com.cloechaudron.planning

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Fournit la grille des jours (collection GridView) au widget calendrier. */
class PlanningWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return CalendarFactory(applicationContext, id)
    }
}

class CalendarFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var cells: List<DayCell> = emptyList()
    private var year = 0
    private var month0 = 0

    override fun onCreate() {}

    override fun onDestroy() {
        cells = emptyList()
    }

    /** Thread worker → le réseau est autorisé ici. */
    override fun onDataSetChanged() {
        val (y, m) = PlanningRepository.displayedMonth(PlanningRepository.offsetOf(context, appWidgetId))
        year = y
        month0 = m
        val entries = PlanningRepository.calendarEntries(PlanningRepository.journees(context))
        cells = if (PlanningRepository.hasCache(context)) {
            PlanningRepository.buildMonth(entries, year, month0)
        } else {
            emptyList()
        }
    }

    override fun getCount(): Int = cells.size

    override fun getViewAt(position: Int): RemoteViews {
        val cell = cells[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_cell)

        if (cell.day == 0) {
            rv.setTextViewText(R.id.cell_text, "")
            rv.setViewVisibility(R.id.cell_fill, View.GONE)
            rv.setViewVisibility(R.id.cell_stroke, View.GONE)
            rv.setViewVisibility(R.id.cell_today, View.GONE)
            rv.setViewVisibility(R.id.cell_badge, View.GONE)
            rv.setViewVisibility(R.id.cell_essai, View.GONE)
            rv.setViewVisibility(R.id.cell_planning, View.GONE)
            return rv
        }

        rv.setViewVisibility(R.id.cell_fill, View.VISIBLE)
        rv.setViewVisibility(R.id.cell_stroke, View.VISIBLE)
        rv.setInt(R.id.cell_fill, "setColorFilter", cell.fillColor)
        rv.setInt(R.id.cell_stroke, "setColorFilter", cell.strokeColor)
        rv.setViewVisibility(R.id.cell_today, if (cell.isToday) View.VISIBLE else View.GONE)

        rv.setTextViewText(R.id.cell_text, cell.day.toString())
        rv.setTextColor(R.id.cell_text, cell.textColor)

        if (cell.count > 1) {
            rv.setViewVisibility(R.id.cell_badge, View.VISIBLE)
            rv.setTextViewText(R.id.cell_badge, cell.count.toString())
        } else {
            rv.setViewVisibility(R.id.cell_badge, View.GONE)
        }

        rv.setViewVisibility(R.id.cell_essai, if (cell.missingEssai) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.cell_planning, if (cell.missingPlanning) View.VISIBLE else View.GONE)

        // Seuls les jours avec événements ouvrent le détail (date via fill-in).
        if (cell.hasEvents) {
            val dateStr = String.format("%02d/%02d/%04d", cell.day, month0 + 1, year)
            rv.setOnClickFillInIntent(
                R.id.cell_root,
                Intent().putExtra(PlanningWidgetProvider.EXTRA_DATE, dateStr),
            )
        }
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
