package com.cloechaudron.planning

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Un événement du planning, déjà transformé comme le fait initData() côté Angular :
 * les lignes "essai" brutes sont exclues, et des essais sont recréés à partir du
 * champ essai.date de chaque journée.
 */
data class Booking(
    val statut: String,       // reserve / demande / essai / autre / perso / persofull
    val etape: Int,           // 999 = terminé
    val hasPlanning: Boolean, // planning.date renseigné ?
    val hasEssai: Boolean,    // essai.date renseigné ?
) {
    /** Reproduit getClass() : etape 999 -> "over" (noir), sinon le statut. */
    fun cssClass(): String = if (etape == 999) "over" else statut
}

/** Bord spécial d'une case (marqueur de l'app). */
enum class Border { NONE, TODAY, NOPLANNING, NOESSAI }

/** Une case du calendrier (un jour affiché, ou une case vide hors mois). */
data class DayCell(
    val day: Int,          // 0 = case vide (hors mois)
    val fillColor: Int,    // couleur de fond
    val textColor: Int,
    val border: Border,
    val count: Int,        // nombre d'événements ce jour (badge si > 1)
)

/**
 * Résultat d'un test de connexion (écran d'aide). Contrairement à fetch(), ne
 * masque rien : sert à savoir POURQUOI le widget reste vide.
 */
data class Diagnostic(
    val url: String,
    val httpCode: Int,        // -1 = exception réseau (voir error)
    val contentType: String,
    val error: String?,
    val bodyLength: Int,
    val bodyPreview: String,  // premiers caractères de la réponse
    val parsedDays: Int,      // nb de jours distincts ayant ≥ 1 événement
    val parsedEvents: Int,    // nb total d'événements parsés
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
            appendLine("Jours avec événements : $parsedDays")
            appendLine("Événements parsés : $parsedEvents")
            appendLine()
            appendLine("Aperçu du corps :")
            append(if (bodyPreview.isBlank()) "(vide)" else bodyPreview)
        }
    }
}

/** Un événement à venir, prêt à afficher dans le widget « prochain événement ». */
data class EventInfo(
    val millis: Long,        // début de journée, pour le tri
    val dateLabel: String,   // ex. "samedi 12 juillet 2026"
    val typeLabel: String,   // ex. "Mariage réservé"
    val css: String,         // reserve/demande/essai/autre/over/perso (couleur)
    val details: String,     // lignes "Libellé : valeur" séparées par \n
)

/** Couleurs reprises 1:1 du SCSS de l'app. */
object Palette {
    val RESERVE = Color.rgb(41, 114, 197)   // .reserve
    val DEMANDE = Color.rgb(241, 50, 225)   // .demande
    val ESSAI = Color.rgb(182, 118, 0)      // .essai
    val AUTRE = Color.rgb(175, 167, 118)    // .autre
    val OVER = Color.BLACK                  // .over (etape 999)
    val PERSO = Color.rgb(198, 40, 40)      // perso/persofull (rouge "indispo")
    val AVAILABLE = Color.WHITE             // jour libre

    val TEXT_DARK = Color.rgb(51, 51, 51)
    val TEXT_LIGHT = Color.WHITE
    val TEXT_PAST = Color.argb(110, 51, 51, 51) // .ant : numéro grisé

    private val DARK_FILLS = setOf("reserve", "demande", "essai", "autre", "over", "perso", "persofull")

    fun fillFor(css: String): Int = when (css) {
        "reserve" -> RESERVE
        "demande" -> DEMANDE
        "essai" -> ESSAI
        "autre" -> AUTRE
        "over" -> OVER
        "perso", "persofull" -> PERSO
        else -> AVAILABLE
    }

    fun isDark(css: String): Boolean = css in DARK_FILLS
}

object PlanningRepository {

    /** Source de données : même endpoint que getData() de l'app. */
    private const val ENDPOINT =
        "http://cloechaudronbeauty.com/backend/api/cloeplanning.php?artiste=cloe"

    private const val PREFS = "planning_widget"
    private const val KEY_JSON = "cache_json"
    private const val KEY_TIME = "cache_time"
    private const val TTL_MS = 30 * 60 * 1000L // 30 min

    val MONTHS = arrayOf(
        "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
        "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- État par widget (mois affiché) ---

    fun offsetOf(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt("offset_$appWidgetId", 0)

    fun setOffset(context: Context, appWidgetId: Int, value: Int) {
        prefs(context).edit().putInt("offset_$appWidgetId", value).apply()
    }

    fun changeOffset(context: Context, appWidgetId: Int, delta: Int) {
        setOffset(context, appWidgetId, offsetOf(context, appWidgetId) + delta)
    }

    /** Mois affiché = mois courant décalé de [offset] mois. Retourne (année, mois 0-based). */
    fun displayedMonth(offset: Int): Pair<Int, Int> {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, offset)
        return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
    }

    // --- Données ---

    fun hasCache(context: Context): Boolean =
        prefs(context).getString(KEY_JSON, null) != null

    /** Force un re-fetch au prochain load(). */
    fun invalidate(context: Context) {
        prefs(context).edit().putLong(KEY_TIME, 0).apply()
    }

    /**
     * Renvoie le JSON brut du planning : cache si frais (< TTL), sinon réseau.
     * En cas d'échec réseau, retombe sur le dernier cache (peut être null).
     * À appeler depuis un thread worker (réseau).
     */
    fun loadJson(context: Context): String? {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        var json = p.getString(KEY_JSON, null)
        val age = now - p.getLong(KEY_TIME, 0)
        if (json == null || age > TTL_MS) {
            val fresh = fetch()
            if (fresh != null) {
                json = fresh
                p.edit().putString(KEY_JSON, fresh).putLong(KEY_TIME, now).apply()
            }
        }
        return json
    }

    /**
     * Charge les événements pour le calendrier (map jour -> liste).
     * Appelé depuis onDataSetChanged() (thread worker), donc le réseau est autorisé.
     */
    fun load(context: Context): Map<String, List<Booking>> {
        val json = loadJson(context)
        return if (json != null) parse(json) else emptyMap()
    }

    /**
     * Charge les événements À VENIR (date >= aujourd'hui), triés par date
     * croissante, pour le widget « prochain événement ». Réseau autorisé.
     */
    fun loadEvents(context: Context): List<EventInfo> {
        val json = loadJson(context) ?: return emptyList()
        return parseEvents(json)
    }

    private fun fetch(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Test de connexion pour l'écran d'aide. Utilise EXACTEMENT le même endpoint
     * et le même parseur que le widget, mais ne masque pas les erreurs : renvoie
     * le code HTTP, un extrait du corps et le nombre d'événements parsés.
     * À appeler depuis un thread worker (réseau).
     */
    fun diagnose(): Diagnostic {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val map = if (code in 200..299) parse(body) else emptyMap()
            Diagnostic(
                url = ENDPOINT,
                httpCode = code,
                contentType = conn.contentType ?: "?",
                error = null,
                bodyLength = body.length,
                bodyPreview = body.take(300),
                parsedDays = map.size,
                parsedEvents = map.values.sumOf { it.size },
            )
        } catch (e: Exception) {
            Diagnostic(
                url = ENDPOINT,
                httpCode = -1,
                contentType = "?",
                error = "${e.javaClass.simpleName}: ${e.message}",
                bodyLength = 0,
                bodyPreview = "",
                parsedDays = 0,
                parsedEvents = 0,
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parse la réponse API en map "dd/MM/yyyy" (zéro-paddé) -> liste d'événements.
     * Reproduit initData : les lignes statut == 'essai' sont exclues, puis des
     * essais sont ajoutés *après* à partir du champ essai.date (d'où les 2 passes,
     * pour que l'événement principal d'un jour reste le mariage/la demande).
     */
    private fun parse(json: String): Map<String, List<Booking>> {
        val map = HashMap<String, MutableList<Booking>>()
        val arr = try { JSONArray(json) } catch (e: Exception) { return map }
        val todayStart = startOfToday()

        // 1) Événements directs (toutes les lignes sauf statut == 'essai')
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("statut", "") == "essai") continue
            val date = o.optString("date", "")
            if (!isValidDate(date)) continue
            val planning = o.optJSONObject("planning")
            val essai = o.optJSONObject("essai")
            map.getOrPut(normalize(date)) { mutableListOf() }.add(
                Booking(
                    statut = o.optString("statut", ""),
                    etape = o.optInt("etape", 0),
                    hasPlanning = !(planning?.optString("date", "").isNullOrBlank()),
                    hasEssai = !(essai?.optString("date", "").isNullOrBlank()),
                )
            )
        }

        // 2) Essais recréés depuis essai.date (ajoutés après, comme initData)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val essaiDate = o.optJSONObject("essai")?.optString("date", "") ?: ""
            if (!isValidDate(essaiDate)) continue
            val past = dateMillis(essaiDate) < todayStart
            map.getOrPut(normalize(essaiDate)) { mutableListOf() }.add(
                Booking("essai", if (past) 999 else o.optInt("etape", 0), true, true)
            )
        }
        return map
    }

    private fun normalize(ddMMyyyy: String): String {
        val (d, m, y) = ddMMyyyy.split("/").map { it.trim().toInt() }
        return String.format("%02d/%02d/%04d", d, m, y)
    }

    /** Construit les 42 cases (6 semaines) du mois affiché, lundi en premier. */
    fun buildMonth(events: Map<String, List<Booking>>, year: Int, month0: Int): List<DayCell> {
        val cells = ArrayList<DayCell>(42)
        val cal = Calendar.getInstance()
        cal.set(year, month0, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val firstDow = cal.get(Calendar.DAY_OF_WEEK) // 1 = dimanche .. 7 = samedi
        val lead = if (firstDow == Calendar.SUNDAY) 6 else firstDow - Calendar.MONDAY
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayStart = startOfToday()

        repeat(lead) { cells.add(emptyCell()) }

        for (day in 1..daysInMonth) {
            val dateStr = String.format("%02d/%02d/%04d", day, month0 + 1, year)
            val millis = dayMillis(year, month0, day)
            val isPast = millis < todayStart
            val isToday = millis == todayStart
            val list = events[dateStr].orEmpty()

            if (list.isEmpty()) {
                cells.add(
                    DayCell(
                        day = day,
                        fillColor = Palette.AVAILABLE,
                        textColor = if (isPast) Palette.TEXT_PAST else Palette.TEXT_DARK,
                        border = if (isToday) Border.TODAY else Border.NONE,
                        count = 0,
                    )
                )
            } else {
                val primary = list[0] // isOccupied/noPlanning utilisent find() = 1er match
                val css = primary.cssClass()
                val border = when {
                    isToday -> Border.TODAY
                    primary.statut == "reserve" && primary.etape != 999 && !primary.hasPlanning -> Border.NOPLANNING
                    primary.statut == "reserve" && primary.etape != 999 && !primary.hasEssai -> Border.NOESSAI
                    else -> Border.NONE
                }
                cells.add(
                    DayCell(
                        day = day,
                        fillColor = Palette.fillFor(css),
                        textColor = if (Palette.isDark(css)) Palette.TEXT_LIGHT else Palette.TEXT_DARK,
                        border = border,
                        count = list.size,
                    )
                )
            }
        }

        while (cells.size < 42) cells.add(emptyCell())
        return cells
    }

    private fun emptyCell() = DayCell(0, Color.TRANSPARENT, Color.TRANSPARENT, Border.NONE, 0)

    // --- Événements à venir (widget "prochain événement") ---

    private val WEEKDAYS = arrayOf(
        "dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
    )

    /** Parse le JSON en liste d'événements à venir, triés par date croissante. */
    private fun parseEvents(json: String): List<EventInfo> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val todayStart = startOfToday()
        val out = ArrayList<EventInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Les essais (statut == 'essai') apparaissent en détail de leur
            // événement (champ essai.date), comme dans le calendrier.
            if (o.optString("statut", "") == "essai") continue
            val date = o.optString("date", "")
            if (!isValidDate(date)) continue
            val millis = dateMillis(date)
            if (millis < todayStart) continue // exclut les événements passés
            out.add(buildEventInfo(o, millis))
        }
        out.sortBy { it.millis }
        return out
    }

    private fun buildEventInfo(o: JSONObject, millis: Long): EventInfo {
        val statut = o.optString("statut", "")
        val etape = o.optInt("etape", 0)
        return EventInfo(
            millis = millis,
            dateLabel = longDateLabel(millis),
            typeLabel = typeLabel(statut, etape),
            css = if (etape == 999) "over" else statut,
            details = buildDetails(o),
        )
    }

    private fun typeLabel(statut: String, etape: Int): String = when {
        etape == 999 -> "Terminé"
        statut == "reserve" -> "Mariage réservé"
        statut == "demande" -> "Demande de mariage"
        statut == "essai" -> "Essai"
        statut == "autre" -> "Autre (shooting, tournage…)"
        statut == "perso" || statut == "persofull" -> "Perso"
        else -> statut.ifBlank { "Événement" }
    }

    private fun longDateLabel(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val dow = WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = MONTHS[c.get(Calendar.MONTH)].lowercase()
        val year = c.get(Calendar.YEAR)
        return "$dow $day $month $year"
    }

    /**
     * Construit les lignes de détail d'un événement. Rendu générique pour
     * l'instant : dates d'essai/planning connues, puis tous les champs scalaires
     * non vides. (À affiner quand on connaîtra le schéma JSON exact.)
     */
    private fun buildDetails(o: JSONObject): String {
        val lines = ArrayList<String>()
        o.optJSONObject("essai")?.optString("date")
            ?.takeIf { it.isNotBlank() }?.let { lines.add("Essai : $it") }
        o.optJSONObject("planning")?.optString("date")
            ?.takeIf { it.isNotBlank() }?.let { lines.add("Planning : $it") }

        val skip = setOf("date", "statut", "etape", "essai", "planning")
        for (key in o.keys().asSequence().toList().sorted()) {
            if (key in skip) continue
            val text = when (val v = o.opt(key)) {
                is String -> v.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
                is Boolean -> if (v) "oui" else null
                is Int, is Long, is Double -> v.toString()
                else -> null // objets/tableaux ignorés en v1
            } ?: continue
            lines.add("${prettify(key)} : $text")
        }
        return if (lines.isEmpty()) "Aucune info supplémentaire." else lines.joinToString("\n")
    }

    private fun prettify(key: String): String =
        key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    // --- Utilitaires date ---

    private fun isValidDate(s: String): Boolean {
        val parts = s.split("/")
        if (parts.size != 3) return false
        val d = parts[0].trim().toIntOrNull() ?: return false
        val m = parts[1].trim().toIntOrNull() ?: return false
        val y = parts[2].trim().toIntOrNull() ?: return false
        return y in 2000..2100 && m in 1..12 && d in 1..31
    }

    private fun dateMillis(ddMMyyyy: String): Long {
        val (d, m, y) = ddMMyyyy.split("/").map { it.trim().toInt() }
        return dayMillis(y, m - 1, d)
    }

    private fun dayMillis(year: Int, month0: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month0, day, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
