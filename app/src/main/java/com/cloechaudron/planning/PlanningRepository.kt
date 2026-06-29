package com.cloechaudron.planning

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** Une case du calendrier (jour affiché, ou case vide hors mois). */
data class DayCell(
    val day: Int,            // 0 = case vide
    val fillColor: Int,
    val strokeColor: Int,
    val textColor: Int,
    val isToday: Boolean,
    val count: Int,          // nombre d'événements ce jour (badge si > 1)
    val name: String,        // nom de la 1re mariée (si la place le permet)
    val missingEssai: Boolean,
    val missingPlanning: Boolean,
    val hasEvents: Boolean,  // jour cliquable (ouvre le détail)
)

/** Résultat d'un test de connexion (écran d'aide). */
data class Diagnostic(
    val url: String,
    val httpCode: Int,
    val contentType: String,
    val error: String?,
    val bodyLength: Int,
    val bodyPreview: String,
    val parsedJournees: Int,
    val parsedUpcoming: Int,
) {
    fun summary(): String = buildString {
        appendLine("URL :")
        appendLine(url)
        appendLine()
        if (httpCode == -1) {
            appendLine("❌ Échec réseau")
            appendLine(error ?: "erreur inconnue")
        } else {
            val ok = httpCode in 200..299
            appendLine("${if (ok) "✅" else "❌"} HTTP $httpCode  ($contentType)")
            appendLine("Corps reçu : $bodyLength caractères")
            appendLine("Journées chargées : $parsedJournees")
            appendLine("Événements à venir : $parsedUpcoming")
            appendLine()
            appendLine("Aperçu du corps :")
            append(if (bodyPreview.isBlank()) "(vide)" else bodyPreview)
        }
    }
}

/**
 * Accès aux données du planning (lecture seule). Reprend l'endpoint du *nouveau*
 * IntraCCB2 — désormais en **HTTPS** — avec mise en cache. Le réseau n'est
 * sollicité que si rien n'est en cache ou si un rafraîchissement a été demandé
 * (widget « Actualiser »). Naviguer (mois, événement, bilan) ne fetch jamais.
 */
object PlanningRepository {

    /** Même source que `getJournees()` de l'app (HTTPS, sans www → www). */
    private const val ENDPOINT =
        "https://www.cloechaudronbeauty.com/backend/api/cloeplanning.php?artiste=cloe"

    private const val PREFS = "planning_widget"
    private const val KEY_JSON = "cache_json"
    private const val KEY_TIME = "cache_time" // 0 = à rafraîchir

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- État par widget ------------------------------------------------------

    fun offsetOf(context: Context, id: Int): Int = prefs(context).getInt("offset_$id", 0)
    fun setOffset(context: Context, id: Int, value: Int) {
        prefs(context).edit().putInt("offset_$id", value).apply()
    }
    fun changeOffset(context: Context, id: Int, delta: Int) {
        setOffset(context, id, offsetOf(context, id) + delta)
    }

    fun eventIndexOf(context: Context, id: Int): Int = prefs(context).getInt("event_index_$id", 0)
    fun setEventIndex(context: Context, id: Int, value: Int) {
        prefs(context).edit().putInt("event_index_$id", value).apply()
    }

    /** Bilan : année affichée (défaut = année courante). */
    fun bilanYearOf(context: Context, id: Int): Int =
        prefs(context).getInt("bilan_year_$id", Calendar.getInstance().get(Calendar.YEAR))
    fun setBilanYear(context: Context, id: Int, value: Int) {
        prefs(context).edit().putInt("bilan_year_$id", value).apply()
    }

    /** Bilan : mois affiché (0 = toute l'année, 1..12 sinon). */
    fun bilanMonthOf(context: Context, id: Int): Int = prefs(context).getInt("bilan_month_$id", 0)
    fun setBilanMonth(context: Context, id: Int, value: Int) {
        prefs(context).edit().putInt("bilan_month_$id", ((value % 13) + 13) % 13).apply()
    }

    /** Mois affiché = mois courant décalé de [offset]. Retourne (année, mois 0-based). */
    fun displayedMonth(offset: Int): Pair<Int, Int> {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, offset)
        return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
    }

    // --- Cache & réseau -------------------------------------------------------

    fun hasCache(context: Context): Boolean = prefs(context).getString(KEY_JSON, null) != null

    /** Force un re-fetch au prochain accès. */
    fun invalidate(context: Context) {
        prefs(context).edit().putLong(KEY_TIME, 0).apply()
    }

    /** JSON brut du planning (réseau uniquement si rien en cache ou invalidé). */
    private fun loadJson(context: Context): String? {
        val p = prefs(context)
        var json = p.getString(KEY_JSON, null)
        val needsRefresh = p.getLong(KEY_TIME, 0) == 0L
        if (json == null || needsRefresh) {
            val fresh = fetch()
            if (fresh != null) {
                json = fresh
                p.edit().putString(KEY_JSON, fresh)
                    .putLong(KEY_TIME, System.currentTimeMillis()).apply()
            }
        }
        return json
    }

    /** Toutes les journées (telles que renvoyées par l'API). Thread worker. */
    fun journees(context: Context): List<Journee> {
        val json = loadJson(context) ?: return emptyList()
        return JourneeParser.parse(json)
    }

    // --- Sélecteurs dérivés (équivalents de PlanningStore) --------------------

    /**
     * Entrées du BILAN : journées (hors statut « essai ») + une entrée « essai »
     * reconstruite à la date d'essai de chaque journée. Inclut les annulés.
     */
    fun statsEntries(journees: List<Journee>): List<Journee> {
        val base = journees.filter { it.statut != Statut.ESSAI }
        val essais = ArrayList<Journee>()
        for (j in base) {
            val ed = j.essai.date
            if (!ed.isNullOrBlank()) {
                essais.add(
                    Journee(
                        date = ed,
                        statut = Statut.ESSAI,
                        etape = if (FrDate.isPast(ed)) ETAPE_TERMINE else j.etape,
                        client = j.client,
                        essai = j.essai,
                        mariage = j.mariage,
                    )
                )
            }
        }
        return base + essais
    }

    /** Entrées du CALENDRIER : comme statsEntries, mais sans les annulés. */
    fun calendarEntries(journees: List<Journee>): List<Journee> =
        statsEntries(journees).filter { it.statut != Statut.ANNULE }

    fun entriesOn(entries: List<Journee>, date: String): List<Journee> =
        entries.filter { it.date == date }

    // --- Cartes d'événements --------------------------------------------------

    /** Événements à venir, triés par date puis heure. Thread worker. */
    fun upcomingEvents(context: Context): List<EventCard> {
        val today = FrDate.startOfToday()
        val nowMin = nowMinutes()
        return calendarEntries(journees(context))
            .map { EventCards.build(it) }
            .filter { stillUpcoming(it.millis, today, nowMin, it.departMin) }
            .sortedWith(compareBy({ it.millis }, { it.startMin }))
    }

    /** Tous les événements d'une date précise (passés ou futurs). Thread worker. */
    fun eventsOn(context: Context, date: String): List<EventCard> =
        entriesOn(calendarEntries(journees(context)), date)
            .map { EventCards.build(it) }
            .sortedWith(compareBy({ it.millis }, { it.startMin }))

    private fun stillUpcoming(millis: Long, today: Long, nowMin: Int, departMin: Int?): Boolean = when {
        millis < today -> false
        millis > today -> true
        else -> departMin == null || nowMin <= departMin
    }

    // --- Construction du mois -------------------------------------------------

    /** 42 cases (6 semaines), lundi en premier, look « teinté » du nouveau calendrier. */
    fun buildMonth(entries: List<Journee>, year: Int, month0: Int): List<DayCell> {
        val byDate = HashMap<String, MutableList<Journee>>()
        for (e in entries) byDate.getOrPut(e.date) { mutableListOf() }.add(e)

        val cells = ArrayList<DayCell>(42)
        val cal = Calendar.getInstance()
        cal.set(year, month0, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val firstDow = cal.get(Calendar.DAY_OF_WEEK) // 1 = dimanche
        val lead = if (firstDow == Calendar.SUNDAY) 6 else firstDow - Calendar.MONDAY
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = FrDate.startOfToday()

        repeat(lead) { cells.add(emptyCell()) }

        for (day in 1..daysInMonth) {
            val dateStr = String.format("%02d/%02d/%04d", day, month0 + 1, year)
            val millis = FrDate.dayMillis(year, month0, day)
            val isPast = millis < today
            val isToday = millis == today
            val list = byDate[dateStr].orEmpty()

            if (list.isEmpty()) {
                val text = if (isPast) Palette.mix(Palette.TEXT_MUTED, 0.5, Palette.SURFACE) else Palette.TEXT_MUTED
                val stroke = if (isPast) Palette.mix(Palette.BORDER, 0.5, Palette.SURFACE) else Palette.BORDER
                cells.add(DayCell(day, Palette.SURFACE, stroke, text, isToday, 0, "", false, false, false))
            } else {
                val first = list[0]
                val hue = Statuts.hue(first.statut, first.etape)
                val reserveActive = first.statut == Statut.RESERVE && first.etape != ETAPE_TERMINE
                var fill = Statuts.cellFill(hue)
                var stroke = Statuts.cellStroke(hue)
                var text = Statuts.darkOnTint(hue)
                if (isPast) {
                    fill = Palette.mix(fill, 0.55, Palette.SURFACE)
                    stroke = Palette.mix(stroke, 0.55, Palette.SURFACE)
                    text = Palette.mix(text, 0.5, Palette.SURFACE)
                }
                cells.add(
                    DayCell(
                        day = day,
                        fillColor = fill,
                        strokeColor = stroke,
                        textColor = text,
                        isToday = isToday,
                        count = list.size,
                        name = first.client.nom?.trim().orEmpty(),
                        missingEssai = reserveActive && first.essai.date.isNullOrBlank(),
                        missingPlanning = reserveActive && first.planning?.date.isNullOrBlank(),
                        hasEvents = true,
                    )
                )
            }
        }
        while (cells.size < 42) cells.add(emptyCell())
        return cells
    }

    private fun emptyCell() = DayCell(
        0, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT, false, 0, "", false, false, false,
    )

    // --- Réseau ---------------------------------------------------------------

    private fun freshUrl(): String = "$ENDPOINT&_=${System.currentTimeMillis()}"

    private fun fetch(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(freshUrl())
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Test de connexion pour l'écran d'aide (mêmes endpoint et parseur). Thread worker. */
    fun diagnose(): Diagnostic {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(freshUrl())
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val journees = if (code in 200..299) JourneeParser.parse(body) else emptyList()
            val today = FrDate.startOfToday()
            val now = nowMinutes()
            val upcoming = calendarEntries(journees)
                .map { EventCards.build(it) }
                .count { stillUpcoming(it.millis, today, now, it.departMin) }
            Diagnostic(
                url = ENDPOINT,
                httpCode = code,
                contentType = conn.contentType ?: "?",
                error = null,
                bodyLength = body.length,
                bodyPreview = body.take(300),
                parsedJournees = journees.size,
                parsedUpcoming = upcoming,
            )
        } catch (e: Exception) {
            Diagnostic(ENDPOINT, -1, "?", "${e.javaClass.simpleName}: ${e.message}", 0, "", 0, 0)
        } finally {
            conn?.disconnect()
        }
    }
}
