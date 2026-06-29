package com.cloechaudron.planning

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

/**
 * Widget « prochain événement » (lecture seule) : un événement à venir à la fois,
 * trié par date puis heure (essai du matin avant mariage de l'après-midi). Affiche
 * type, mariée, lieu (+ cérémonie), horaires du jour-J et reste à payer. Flèches
 * ◀ ▶ pour parcourir ; compteur = rafraîchir ; tap sur la carte = fiche détail.
 */
class EventWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAsync(context, mgr, ids, goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_PREV || action == ACTION_NEXT || action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID)
            val ids = if (id != INVALID) intArrayOf(id) else widgetIds(context, mgr)
            when (action) {
                ACTION_PREV -> changeIndex(context, id, -1)
                ACTION_NEXT -> changeIndex(context, id, +1)
                ACTION_REFRESH -> {
                    PlanningRepository.invalidate(context)
                    for (t in ids) PlanningRepository.setEventIndex(context, t, 0)
                }
            }
            renderAsync(context, mgr, ids, goAsync())
            return
        }
        super.onReceive(context, intent)
    }

    private fun renderAsync(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray,
        pending: BroadcastReceiver.PendingResult?,
    ) {
        Thread {
            try {
                val events = PlanningRepository.upcomingEvents(context)
                for (id in ids) renderWidget(context, mgr, id, events)
            } finally {
                pending?.finish()
            }
        }.start()
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int, events: List<EventCard>) {
        val views = RemoteViews(context.packageName, R.layout.widget_event)

        if (events.isEmpty()) {
            views.setViewVisibility(R.id.event_content, View.GONE)
            views.setViewVisibility(R.id.event_empty, View.VISIBLE)
            views.setTextViewText(R.id.event_counter, "0 / 0")
        } else {
            val index = PlanningRepository.eventIndexOf(context, id).coerceIn(0, events.size - 1)
            PlanningRepository.setEventIndex(context, id, index)
            val e = events[index]

            views.setViewVisibility(R.id.event_empty, View.GONE)
            views.setViewVisibility(R.id.event_content, View.VISIBLE)
            views.setTextViewText(R.id.event_counter, "${index + 1} / ${events.size}")
            views.setTextViewText(R.id.event_date, e.dateLabel)

            val sameDay = events.count { it.millis == e.millis }
            if (sameDay > 1) {
                views.setViewVisibility(R.id.event_daycount, View.VISIBLE)
                views.setTextViewText(R.id.event_daycount, "$sameDay événements ce jour")
            } else {
                views.setViewVisibility(R.id.event_daycount, View.GONE)
            }

            views.setTextViewText(R.id.event_type, e.typeLabel)
            views.setTextColor(R.id.event_type, e.hue)
            setOrHide(views, R.id.event_name, e.name)
            setOrHide(views, R.id.event_lieu, e.lieuLine)
            setOrHide(views, R.id.event_horaires, e.horairesLine)
            setOrHide(views, R.id.event_reste, e.resteLine)

            if (e.phone.isNotBlank()) {
                views.setViewVisibility(R.id.event_phone, View.VISIBLE)
                views.setTextViewText(R.id.event_phone, "📞 ${e.phone}")
                views.setOnClickPendingIntent(R.id.event_phone, dialIntent(context, id, e.phone))
            } else {
                views.setViewVisibility(R.id.event_phone, View.GONE)
            }

            if (e.email.isNotBlank()) {
                views.setViewVisibility(R.id.event_mail, View.VISIBLE)
                views.setTextViewText(R.id.event_mail, "✉ ${e.email}")
                views.setOnClickPendingIntent(R.id.event_mail, mailIntent(context, id, e.email, e.mailSubject))
            } else {
                views.setViewVisibility(R.id.event_mail, View.GONE)
            }

            // Tap ailleurs sur la carte → fiche détail (lecture seule) de cet événement.
            views.setOnClickPendingIntent(R.id.event_content, detailIntent(context, id, e))
        }

        views.setOnClickPendingIntent(R.id.event_prev, navIntent(context, id, ACTION_PREV))
        views.setOnClickPendingIntent(R.id.event_next, navIntent(context, id, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.event_counter, navIntent(context, id, ACTION_REFRESH))

        mgr.updateAppWidget(id, views)
    }

    private fun setOrHide(views: RemoteViews, viewId: Int, text: String) {
        if (text.isNotBlank()) {
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setTextViewText(viewId, text)
        } else {
            views.setViewVisibility(viewId, View.GONE)
        }
    }

    private fun changeIndex(context: Context, id: Int, delta: Int) {
        if (id == INVALID) return
        val next = (PlanningRepository.eventIndexOf(context, id) + delta).coerceAtLeast(0)
        PlanningRepository.setEventIndex(context, id, next)
    }

    // --- Intents --------------------------------------------------------------

    private fun detailIntent(context: Context, id: Int, e: EventCard): PendingIntent {
        val intent = Intent(context, DayDetailActivity::class.java)
            .putExtra(PlanningWidgetProvider.EXTRA_DATE, e.date)
            .putExtra(DayDetailActivity.EXTRA_START, e.startMin)
        return PendingIntent.getActivity(
            context, REQ_OPEN + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dialIntent(context: Context, id: Int, phone: String): PendingIntent =
        PendingIntent.getActivity(
            context, REQ_DIAL + id,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", ""))),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun mailIntent(context: Context, id: Int, email: String, subject: String): PendingIntent {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email?subject=" + Uri.encode(subject)))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
        return PendingIntent.getActivity(
            context, REQ_MAIL + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun navIntent(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, EventWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("eventwidget://$action/$id")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun widgetIds(context: Context, mgr: AppWidgetManager): IntArray =
        mgr.getAppWidgetIds(ComponentName(context, EventWidgetProvider::class.java))

    companion object {
        const val ACTION_PREV = "com.cloechaudron.planning.EVENT_PREV"
        const val ACTION_NEXT = "com.cloechaudron.planning.EVENT_NEXT"
        const val ACTION_REFRESH = "com.cloechaudron.planning.EVENT_REFRESH"

        private const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val REQ_OPEN = 200
        private const val REQ_DIAL = 1000
        private const val REQ_MAIL = 2000
    }
}
