package com.cloechaudron.planning

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Widget « Bilan financier » (lecture seule) — port des indicateurs du tableau de
 * bord du nouveau IntraCCB2 (`StatsService`) : total, encaissé, reste à encaisser,
 * estimation, net (après abattement + pourboires), heures travaillées, compteurs
 * de statuts, et un petit graphe du revenu mensuel de l'année. Flèches du haut =
 * année, du bas = mois (ou « toute l'année ») ; tap sur l'année revient à
 * aujourd'hui ; aucune navigation ne refetch.
 */
class BilanWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAsync(context, mgr, ids, goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action in NAV_ACTIONS) {
            val mgr = AppWidgetManager.getInstance(context)
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID)
            val ids = if (id != INVALID) intArrayOf(id) else widgetIds(context, mgr)
            if (id != INVALID) when (action) {
                ACTION_YEAR_PREV -> PlanningRepository.setBilanYear(context, id, PlanningRepository.bilanYearOf(context, id) - 1)
                ACTION_YEAR_NEXT -> PlanningRepository.setBilanYear(context, id, PlanningRepository.bilanYearOf(context, id) + 1)
                ACTION_MONTH_PREV -> PlanningRepository.setBilanMonth(context, id, PlanningRepository.bilanMonthOf(context, id) - 1)
                ACTION_MONTH_NEXT -> PlanningRepository.setBilanMonth(context, id, PlanningRepository.bilanMonthOf(context, id) + 1)
                ACTION_TODAY -> {
                    PlanningRepository.setBilanYear(context, id, Calendar.getInstance().get(Calendar.YEAR))
                    PlanningRepository.setBilanMonth(context, id, 0)
                }
            }
            if (action == ACTION_REFRESH) PlanningRepository.invalidate(context)
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
                val hasCache = PlanningRepository.hasCache(context)
                val entries = PlanningRepository.statsEntries(PlanningRepository.journees(context))
                for (id in ids) renderWidget(context, mgr, id, entries, hasCache)
            } finally {
                pending?.finish()
            }
        }.start()
    }

    private fun renderWidget(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        entries: List<Journee>,
        hasCache: Boolean,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_bilan)
        val year = PlanningRepository.bilanYearOf(context, id)
        val month0 = PlanningRepository.bilanMonthOf(context, id)
        val month: Int? = if (month0 == 0) null else month0

        views.setTextViewText(R.id.bilan_year, year.toString())
        views.setTextViewText(R.id.bilan_period, if (month0 == 0) "Toute l'année" else FrDate.MONTHS_CAP[month0 - 1])

        views.setOnClickPendingIntent(R.id.bilan_year_prev, nav(context, id, ACTION_YEAR_PREV))
        views.setOnClickPendingIntent(R.id.bilan_year_next, nav(context, id, ACTION_YEAR_NEXT))
        views.setOnClickPendingIntent(R.id.bilan_month_prev, nav(context, id, ACTION_MONTH_PREV))
        views.setOnClickPendingIntent(R.id.bilan_month_next, nav(context, id, ACTION_MONTH_NEXT))
        views.setOnClickPendingIntent(R.id.bilan_year, nav(context, id, ACTION_TODAY))

        if (!hasCache) {
            views.setViewVisibility(R.id.bilan_content, View.GONE)
            views.setViewVisibility(R.id.bilan_empty, View.VISIBLE)
            mgr.updateAppWidget(id, views)
            return
        }
        views.setViewVisibility(R.id.bilan_empty, View.GONE)
        views.setViewVisibility(R.id.bilan_content, View.VISIBLE)

        val total = Stats.totalRevenue(entries, year, month)
        views.setTextViewText(R.id.bilan_total, Money.euro(total))
        views.setTextViewText(R.id.bilan_paid, Money.euro(Stats.alreadyPaid(entries, year, month)))
        views.setTextViewText(R.id.bilan_reste, Money.euro(Stats.notPaid(entries, year, month)))
        views.setTextViewText(R.id.bilan_estimate, Money.euro(Stats.estimate(entries, year, month)))
        views.setTextViewText(R.id.bilan_cash, Money.euro(Stats.cash(entries, year, month)))
        views.setTextViewText(R.id.bilan_net, Money.euro(Stats.net(total) + Stats.cash(entries, year, month)))

        val mins = Stats.hoursWorked(entries, year, month, false, false).toLong()
        val perH = Stats.perHour(entries, year, month, false, false)
        views.setTextViewText(R.id.bilan_hours, "Heures : ${mins / 60}h ${mins % 60}min · ${Money.js(perH)} €/h")
        views.setTextViewText(R.id.bilan_counters, counters(entries, year, month))

        views.setImageViewBitmap(R.id.bilan_chart, chart(entries, year))

        mgr.updateAppWidget(id, views)
    }

    private fun counters(entries: List<Journee>, year: Int, month: Int?): String {
        val r = Stats.statutCount(entries, Statut.RESERVE, year, month)
        val d = Stats.statutCount(entries, Statut.DEMANDE, year, month)
        val e = Stats.statutCount(entries, Statut.ESSAI, year, month)
        val a = Stats.statutCount(entries, Statut.AUTRE, year, month)
        val finished = Stats.etapeCount(entries, ETAPE_TERMINE, year, month)
        val mariages = if (r.over > 0) "${r.still} (+${r.over}✓)" else "${r.still}"
        return "Mariages $mariages · Demandes ${d.still} · Essais ${e.still} · Autres ${a.still} · Terminés $finished"
    }

    /** Petit graphe du revenu mensuel de l'année (barres mauves, mois courant accentué). */
    private fun chart(entries: List<Journee>, year: Int): Bitmap {
        val monthly = Stats.yearSeries(entries).firstOrNull { it.year == year }?.monthly ?: DoubleArray(12)
        val w = 520
        val h = 176
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val padL = 6f
        val padR = 6f
        val padT = 10f
        val padB = 22f
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        val baseY = padT + plotH
        val max = (monthly.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val slot = plotW / 12f
        val barW = slot * 0.6f

        val now = Calendar.getInstance()
        val currentMonth = if (year == now.get(Calendar.YEAR)) now.get(Calendar.MONTH) else -1

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BORDER; strokeWidth = 2f }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.TEXT_MUTED; textSize = 16f; textAlign = Paint.Align.CENTER
        }
        val initials = arrayOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

        canvas.drawLine(padL, baseY, w - padR, baseY, axis)
        for (i in 0 until 12) {
            val v = monthly[i]
            val barH = (v / max * plotH).toFloat()
            val left = padL + i * slot + (slot - barW) / 2f
            val top = baseY - barH
            bar.color = if (i == currentMonth) Palette.BRAND_STRONG else Palette.BRAND
            if (barH > 0f) canvas.drawRoundRect(left, top, left + barW, baseY, 4f, 4f, bar)
            canvas.drawText(initials[i], left + barW / 2f, h - 6f, tick)
        }
        return bmp
    }

    private fun nav(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, BilanWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("bilanwidget://$action/$id")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun widgetIds(context: Context, mgr: AppWidgetManager): IntArray =
        mgr.getAppWidgetIds(ComponentName(context, BilanWidgetProvider::class.java))

    companion object {
        const val ACTION_YEAR_PREV = "com.cloechaudron.planning.BILAN_YEAR_PREV"
        const val ACTION_YEAR_NEXT = "com.cloechaudron.planning.BILAN_YEAR_NEXT"
        const val ACTION_MONTH_PREV = "com.cloechaudron.planning.BILAN_MONTH_PREV"
        const val ACTION_MONTH_NEXT = "com.cloechaudron.planning.BILAN_MONTH_NEXT"
        const val ACTION_TODAY = "com.cloechaudron.planning.BILAN_TODAY"
        const val ACTION_REFRESH = "com.cloechaudron.planning.BILAN_REFRESH"

        private val NAV_ACTIONS = setOf(
            ACTION_YEAR_PREV, ACTION_YEAR_NEXT, ACTION_MONTH_PREV,
            ACTION_MONTH_NEXT, ACTION_TODAY, ACTION_REFRESH,
        )
        private const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
    }
}
