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
 * Deuxième widget : affiche un événement à venir à la fois, avec des flèches
 * ◀ ▶ pour parcourir les événements triés par date (les événements passés sont
 * exclus). Tap sur le compteur = rafraîchir et revenir au prochain événement.
 * Tap sur la carte = ouvre l'intra.
 *
 * Pas de collection (GridView) ici : un seul événement est affiché, donc le
 * provider charge les données sur un thread worker (goAsync) et rend directement.
 */
class EventWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        renderAsync(context, appWidgetManager, appWidgetIds, goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_PREV || action == ACTION_NEXT || action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val ids = if (id != INVALID) intArrayOf(id) else widgetIds(context, mgr)
            when (action) {
                ACTION_PREV -> changeIndex(context, id, -1)
                ACTION_NEXT -> changeIndex(context, id, +1)
                ACTION_REFRESH -> {
                    PlanningRepository.invalidate(context)
                    for (t in ids) setIndex(context, t, 0)
                }
            }
            renderAsync(context, mgr, ids, goAsync())
            return
        }
        super.onReceive(context, intent)
    }

    /** Charge les données sur un thread worker puis met à jour les widgets. */
    private fun renderAsync(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray,
        pending: BroadcastReceiver.PendingResult?,
    ) {
        Thread {
            try {
                val events = PlanningRepository.loadEvents(context)
                for (id in ids) renderWidget(context, mgr, id, events)
            } finally {
                pending?.finish()
            }
        }.start()
    }

    private fun renderWidget(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        events: List<EventInfo>,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_event)

        if (events.isEmpty()) {
            views.setViewVisibility(R.id.event_content, View.GONE)
            views.setViewVisibility(R.id.event_empty, View.VISIBLE)
            views.setTextViewText(R.id.event_counter, "0 / 0")
        } else {
            val index = indexOf(context, id).coerceIn(0, events.size - 1)
            setIndex(context, id, index) // mémorise l'index borné
            val e = events[index]

            views.setViewVisibility(R.id.event_empty, View.GONE)
            views.setViewVisibility(R.id.event_content, View.VISIBLE)
            views.setTextViewText(R.id.event_counter, "${index + 1} / ${events.size}")
            views.setTextViewText(R.id.event_date, e.dateLabel)

            // Nombre d'événements le même jour que celui affiché
            val sameDay = events.count { it.millis == e.millis }
            if (sameDay > 1) {
                views.setViewVisibility(R.id.event_daycount, View.VISIBLE)
                views.setTextViewText(R.id.event_daycount, "$sameDay événements ce jour")
            } else {
                views.setViewVisibility(R.id.event_daycount, View.GONE)
            }

            views.setTextViewText(R.id.event_type, e.typeLabel)
            views.setTextColor(R.id.event_type, typeColor(e.css))
            setOrHide(views, R.id.event_name, e.name)
            setOrHide(views, R.id.event_details, e.details)

            // Téléphone cliquable -> ouvre le composeur avec le numéro
            if (e.phone.isNotBlank()) {
                views.setViewVisibility(R.id.event_phone, View.VISIBLE)
                views.setTextViewText(R.id.event_phone, "📞 ${e.phone}")
                views.setOnClickPendingIntent(R.id.event_phone, dialIntent(context, id, e.phone))
            } else {
                views.setViewVisibility(R.id.event_phone, View.GONE)
            }

            // Mail cliquable -> rédaction avec destinataire + objet pré-remplis
            if (e.email.isNotBlank()) {
                views.setViewVisibility(R.id.event_mail, View.VISIBLE)
                views.setTextViewText(R.id.event_mail, "✉ ${e.email}")
                views.setOnClickPendingIntent(
                    R.id.event_mail, mailIntent(context, id, e.email, e.mailSubject),
                )
            } else {
                views.setViewVisibility(R.id.event_mail, View.GONE)
            }

            // Tap ailleurs sur la carte -> ouvre l'intra
            views.setOnClickPendingIntent(R.id.event_content, openSiteIntent(context))
        }

        views.setOnClickPendingIntent(R.id.event_prev, navIntent(context, id, ACTION_PREV))
        views.setOnClickPendingIntent(R.id.event_next, navIntent(context, id, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.event_counter, navIntent(context, id, ACTION_REFRESH))

        mgr.updateAppWidget(id, views)
    }

    /** Couleur du libellé de type, reprise de la palette (lisible sur fond clair). */
    private fun typeColor(css: String): Int {
        val c = Palette.fillFor(css)
        return if (c == Palette.AVAILABLE) Palette.TEXT_DARK else c
    }

    private fun setOrHide(views: RemoteViews, viewId: Int, text: String) {
        if (text.isNotBlank()) {
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setTextViewText(viewId, text)
        } else {
            views.setViewVisibility(viewId, View.GONE)
        }
    }

    // --- Index courant par widget ---

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun indexOf(context: Context, id: Int): Int =
        prefs(context).getInt("event_index_$id", 0)

    private fun setIndex(context: Context, id: Int, value: Int) {
        prefs(context).edit().putInt("event_index_$id", value).apply()
    }

    private fun changeIndex(context: Context, id: Int, delta: Int) {
        // Pas de borne haute ici (on ne connaît pas encore le nombre d'événements) :
        // renderWidget borne l'index avec les données chargées et le réécrit.
        val next = (indexOf(context, id) + delta).coerceAtLeast(0)
        setIndex(context, id, next)
    }

    // --- Intents ---

    private fun openSiteIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, REQ_OPEN,
            Intent(Intent.ACTION_VIEW, Uri.parse(PlanningWidgetProvider.SITE_URL)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Ouvre le composeur téléphonique avec le numéro pré-rempli (sans appeler). */
    private fun dialIntent(context: Context, id: Int, phone: String): PendingIntent {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", "")))
        return PendingIntent.getActivity(
            context, REQ_DIAL + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Ouvre l'app mail avec destinataire + objet (type - date) pré-remplis. */
    private fun mailIntent(context: Context, id: Int, email: String, subject: String): PendingIntent {
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:$email?subject=" + Uri.encode(subject)),
        ).apply { putExtra(Intent.EXTRA_SUBJECT, subject) }
        return PendingIntent.getActivity(
            context, REQ_MAIL + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun navIntent(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, EventWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // data unique -> PendingIntents distincts par (action, widget)
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

        private const val PREFS = "planning_widget"
        private const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val REQ_OPEN = 200
        private const val REQ_DIAL = 300
        private const val REQ_MAIL = 400
    }
}
