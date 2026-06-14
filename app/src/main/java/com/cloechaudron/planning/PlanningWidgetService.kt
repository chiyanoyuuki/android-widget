package com.cloechaudron.planning

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Fournit la grille des jours (collection GridView) au widget. */
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

    /** Appelé sur un thread worker -> le réseau est autorisé ici. */
    override fun onDataSetChanged() {
        val offset = PlanningRepository.offsetOf(context, appWidgetId)
        val (y, m) = PlanningRepository.displayedMonth(offset)
        year = y
        month0 = m
        val events = PlanningRepository.load(context)
        // Si aucune donnée n'a jamais pu être chargée -> liste vide -> vue "empty".
        cells = if (PlanningRepository.hasCache(context)) {
            PlanningRepository.buildMonth(events, year, month0)
        } else {
            emptyList()
        }
    }

    override fun getCount(): Int = cells.size

    override fun getViewAt(position: Int): RemoteViews {
        val cell = cells[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_cell)

        if (cell.day == 0) {
            // Case vide (hors mois)
            rv.setTextViewText(R.id.cell_text, "")
            rv.setInt(R.id.cell_fill, "setBackgroundColor", Color.TRANSPARENT)
            rv.setViewVisibility(R.id.cell_border, View.GONE)
            rv.setViewVisibility(R.id.cell_badge, View.GONE)
            return rv
        }

        rv.setTextViewText(R.id.cell_text, cell.day.toString())
        rv.setTextColor(R.id.cell_text, cell.textColor)
        rv.setInt(R.id.cell_fill, "setBackgroundColor", cell.fillColor)

        when (cell.border) {
            Border.NONE -> rv.setViewVisibility(R.id.cell_border, View.GONE)
            Border.TODAY -> setBorder(rv, R.drawable.cell_border_today)
            Border.NOPLANNING -> setBorder(rv, R.drawable.cell_border_noplanning)
            Border.NOESSAI -> setBorder(rv, R.drawable.cell_border_noessai)
        }

        if (cell.count > 1) {
            rv.setViewVisibility(R.id.cell_badge, View.VISIBLE)
            rv.setTextViewText(R.id.cell_badge, cell.count.toString())
        } else {
            rv.setViewVisibility(R.id.cell_badge, View.GONE)
        }

        // Seuls les jours avec événements sont cliquables -> ouvre le détail du jour
        // (le template, défini par le provider, lance DayDetailActivity ; la date
        // est fournie par ce fill-in).
        if (cell.hasEvents) {
            val dateStr = String.format("%02d/%02d/%04d", cell.day, month0 + 1, year)
            rv.setOnClickFillInIntent(
                R.id.cell_root,
                Intent().putExtra(PlanningWidgetProvider.EXTRA_DATE, dateStr),
            )
        }
        return rv
    }

    private fun setBorder(rv: RemoteViews, drawable: Int) {
        rv.setViewVisibility(R.id.cell_border, View.VISIBLE)
        rv.setInt(R.id.cell_border, "setBackgroundResource", drawable)
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
