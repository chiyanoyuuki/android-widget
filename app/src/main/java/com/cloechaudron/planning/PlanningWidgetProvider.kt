package com.cloechaudron.planning

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Widget calendrier mensuel (lecture seule), aligné sur le calendrier du nouveau
 * IntraCCB2 : jours « teintés » selon le statut, pastilles « manque essai /
 * planning », anneau du jour. Les flèches changent de mois (ligne du bas) ou
 * d'année (ligne du haut) ; un tap sur le mois/année revient au mois courant ;
 * un tap sur un jour avec événements ouvre la fiche détail (lecture seule).
 */
class PlanningWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mgr = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID)
        when (intent.action) {
            ACTION_PREV -> if (id != INVALID) { PlanningRepository.changeOffset(context, id, -1); showMonth(context, mgr, id) }
            ACTION_NEXT -> if (id != INVALID) { PlanningRepository.changeOffset(context, id, +1); showMonth(context, mgr, id) }
            ACTION_PREV_YEAR -> if (id != INVALID) { PlanningRepository.changeOffset(context, id, -12); showMonth(context, mgr, id) }
            ACTION_NEXT_YEAR -> if (id != INVALID) { PlanningRepository.changeOffset(context, id, +12); showMonth(context, mgr, id) }
            ACTION_TODAY -> if (id != INVALID) { PlanningRepository.setOffset(context, id, 0); showMonth(context, mgr, id) }
            ACTION_REFRESH -> {
                PlanningRepository.invalidate(context)
                for (wid in widgetIds(context, mgr)) renderWidget(context, mgr, wid)
            }
        }
    }

    /** Navigation : met à jour l'en-tête (mois/année) puis rafraîchit la grille. */
    private fun showMonth(context: Context, mgr: AppWidgetManager, id: Int) {
        val (year, month0) = PlanningRepository.displayedMonth(PlanningRepository.offsetOf(context, id))
        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        views.setTextViewText(R.id.year_label, year.toString())
        views.setTextViewText(R.id.month_label, FrDate.MONTHS_CAP[month0])
        mgr.partiallyUpdateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid)
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val offset = PlanningRepository.offsetOf(context, id)
        val (year, month0) = PlanningRepository.displayedMonth(offset)

        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        views.setTextViewText(R.id.year_label, year.toString())
        views.setTextViewText(R.id.month_label, FrDate.MONTHS_CAP[month0])

        val service = Intent(context, PlanningWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
        views.setRemoteAdapter(R.id.calendar_grid, service)
        views.setEmptyView(R.id.calendar_grid, R.id.calendar_empty)

        // Clic sur un jour avec événements → fiche détail (lecture seule).
        val dayTemplate = PendingIntent.getActivity(
            context, REQ_OPEN,
            Intent(context, DayDetailActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        views.setPendingIntentTemplate(R.id.calendar_grid, dayTemplate)

        views.setOnClickPendingIntent(R.id.nav_prev, navIntent(context, id, ACTION_PREV))
        views.setOnClickPendingIntent(R.id.nav_next, navIntent(context, id, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.nav_year_prev, navIntent(context, id, ACTION_PREV_YEAR))
        views.setOnClickPendingIntent(R.id.nav_year_next, navIntent(context, id, ACTION_NEXT_YEAR))
        views.setOnClickPendingIntent(R.id.month_label, navIntent(context, id, ACTION_TODAY))
        views.setOnClickPendingIntent(R.id.year_label, navIntent(context, id, ACTION_TODAY))

        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid)
    }

    private fun navIntent(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, PlanningWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("planningwidget://$action/$id")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun widgetIds(context: Context, mgr: AppWidgetManager): IntArray =
        mgr.getAppWidgetIds(ComponentName(context, PlanningWidgetProvider::class.java))

    companion object {
        const val EXTRA_DATE = "com.cloechaudron.planning.EXTRA_DATE"

        const val ACTION_PREV = "com.cloechaudron.planning.PREV"
        const val ACTION_NEXT = "com.cloechaudron.planning.NEXT"
        const val ACTION_PREV_YEAR = "com.cloechaudron.planning.PREV_YEAR"
        const val ACTION_NEXT_YEAR = "com.cloechaudron.planning.NEXT_YEAR"
        const val ACTION_TODAY = "com.cloechaudron.planning.TODAY"
        const val ACTION_REFRESH = "com.cloechaudron.planning.REFRESH"

        private const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val REQ_OPEN = 100
    }
}
