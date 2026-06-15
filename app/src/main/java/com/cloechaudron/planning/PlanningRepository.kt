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
    val hasEvents: Boolean = false, // jour cliquable (ouvre le détail)
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
    val name: String,        // nom du client ("" si absent)
    val details: String,     // adresse / horaires / reste à payer, séparés par \n
    val phone: String,       // numéro brut ("" si absent)
    val email: String,       // adresse mail ("" si absent)
    val mailSubject: String, // objet pré-rempli du mail
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
    private const val KEY_TIME = "cache_time" // 0 = à rafraîchir, sinon horodatage du dernier fetch

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
     * Renvoie le JSON brut du planning. Réseau UNIQUEMENT si rien en cache, ou
     * si un rafraîchissement a été demandé via invalidate() (widget « Actualiser »).
     * Sinon on sert toujours le cache : changer de mois/année ne fetch jamais.
     * À appeler depuis un thread worker.
     */
    fun loadJson(context: Context): String? {
        val p = prefs(context)
        var json = p.getString(KEY_JSON, null)
        val needsRefresh = p.getLong(KEY_TIME, 0) == 0L // 0 = jamais chargé ou invalidé
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

    /**
     * Tous les événements d'un jour précis (dd/MM/yyyy), passés ou futurs, pour
     * l'écran de détail du calendrier. Réseau autorisé (thread worker).
     */
    fun eventsOn(context: Context, dateStr: String): List<EventInfo> {
        val json = loadJson(context) ?: return emptyList()
        return parseEventsForDate(json, dateStr)
    }

    /** "dd/MM/yyyy" -> "lundi 15 juin 2026" (ou la chaîne brute si invalide). */
    fun longDate(dateStr: String): String =
        if (isValidDate(dateStr)) longDateLabel(dateMillis(dateStr)) else dateStr

    /** URL avec paramètre anti-cache : évite qu'un cache serveur/CDN renvoie un planning périmé. */
    private fun freshUrl(): String = "$ENDPOINT&_=${System.currentTimeMillis()}"

    private fun fetch(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(freshUrl()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
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
            conn = (URL(freshUrl()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
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
                        hasEvents = true,
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

    /**
     * Parse le JSON en liste d'événements à venir, triés par date croissante.
     * Reproduit initData() : les lignes statut == 'essai' sont exclues, puis un
     * événement « essai » est reconstruit pour chaque mariage ayant un essai.date
     * (en reprenant nom/tel/mail du parent). Ainsi les essais apparaissent comme
     * des étapes navigables dans le widget.
     */
    private fun parseEvents(json: String): List<EventInfo> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val todayStart = startOfToday()
        val nowMin = nowMinutes()
        val out = ArrayList<EventInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("statut", "") == "essai") continue // lignes essai brutes exclues

            // Événement principal (mariage / demande / autre / perso)
            val date = o.optString("date", "")
            if (isValidDate(date)) {
                val millis = dateMillis(date)
                if (stillUpcoming(millis, todayStart, nowMin, departureMinutes(o))) {
                    out.add(buildEventInfo(o, millis))
                }
            }

            // Essai reconstruit depuis essai.date (heure de l'essai = heure de départ)
            val essai = o.optJSONObject("essai")
            val essaiDate = essai?.optString("date").orEmpty()
            if (essai != null && isValidDate(essaiDate)) {
                val millis = dateMillis(essaiDate)
                val endMin = parseHm(essai.optString("heure").trim())
                if (stillUpcoming(millis, todayStart, nowMin, endMin)) {
                    out.add(buildEssaiEvent(o, essai, millis))
                }
            }
        }
        out.sortBy { it.millis }
        return out
    }

    /**
     * Garde l'événement s'il est dans le futur, ou aujourd'hui tant que son heure
     * de départ (fin de prestation) n'est pas dépassée. Si pas d'heure connue, on
     * garde l'événement toute la journée.
     */
    private fun stillUpcoming(millis: Long, todayStart: Long, nowMin: Int, departMin: Int?): Boolean =
        when {
            millis < todayStart -> false
            millis > todayStart -> true
            else -> departMin == null || nowMin <= departMin
        }

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Heure de départ (fin de prestation) d'un mariage en minutes, ou null. */
    private fun departureMinutes(o: JSONObject): Int? {
        val p = o.optJSONObject("planning") ?: return null
        parseHm(p.optString("finprestas").trim())?.let { return it }
        val invitees = p.optJSONArray("invitees") ?: return null
        var maxM = Int.MIN_VALUE
        for (i in 0 until invitees.length()) {
            val row = invitees.optJSONArray(i) ?: continue
            for (j in 0 until row.length()) {
                val m = parseHm(row.optString(j, "")) ?: continue
                if (m > maxM) maxM = m
            }
        }
        return if (maxM == Int.MIN_VALUE) null else maxM
    }

    /** Tous les événements (mariages + essais reconstruits) d'une date précise. */
    private fun parseEventsForDate(json: String, dateStr: String): List<EventInfo> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        if (!isValidDate(dateStr)) return emptyList()
        val target = normalize(dateStr)
        val out = ArrayList<EventInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("statut", "") == "essai") continue
            val date = o.optString("date", "")
            if (isValidDate(date) && normalize(date) == target) {
                out.add(buildEventInfo(o, dateMillis(date)))
            }
            val essai = o.optJSONObject("essai")
            val essaiDate = essai?.optString("date").orEmpty()
            if (essai != null && isValidDate(essaiDate) && normalize(essaiDate) == target) {
                out.add(buildEssaiEvent(o, essai, dateMillis(essaiDate)))
            }
        }
        return out
    }

    private fun buildEventInfo(o: JSONObject, millis: Long): EventInfo {
        val statut = o.optString("statut", "")
        val etape = o.optInt("etape", 0)
        val type = typeLabel(statut, etape)
        val date = o.optString("date", "")
        val detailLines = listOf(addressLine(o), scheduleLine(o), resteLine(o))
            .filter { it.isNotBlank() }
        return EventInfo(
            millis = millis,
            dateLabel = longDateLabel(millis),
            typeLabel = type,
            css = if (etape == 999) "over" else statut,
            name = o.optString("nom", "").trim(),
            details = detailLines.joinToString("\n"),
            phone = o.optString("tel", "").trim(),
            email = o.optString("mail", "").trim(),
            mailSubject = "$type - $date",
        )
    }

    /**
     * Événement « essai » reconstruit à partir de l'essai d'un mariage : reprend
     * le contact du parent, et affiche le lieu (+ heure) de l'essai.
     */
    private fun buildEssaiEvent(parent: JSONObject, essai: JSONObject, millis: Long): EventInfo {
        val lieu = essai.optString("lieu").trim()
        val heure = essai.optString("heure").trim()
        val loc = when {
            lieu.isNotBlank() && heure.isNotBlank() -> "$lieu (à $heure)"
            lieu.isNotBlank() -> lieu
            heure.isNotBlank() -> "à $heure"
            else -> ""
        }
        return EventInfo(
            millis = millis,
            dateLabel = longDateLabel(millis),
            typeLabel = "Essai",
            css = "essai",
            name = parent.optString("nom", "").trim(),
            details = loc,
            phone = parent.optString("tel", "").trim(),
            email = parent.optString("mail", "").trim(),
            mailSubject = "Essai - ${essai.optString("date", "")}",
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
     * Adresse du mariage : "Domaine, adresse codepostal".
     * Priorité aux objets `mariage` puis `planning`, sinon aux champs racine.
     * Ajoute " (à HHhMM)" si une heure de cérémonie est connue.
     */
    private fun addressLine(o: JSONObject): String {
        var domaine = ""
        var adresse = ""
        var cp = ""
        for (s in listOfNotNull(o.optJSONObject("mariage"), o.optJSONObject("planning"))) {
            if (domaine.isBlank()) domaine = s.optString("domaine").trim()
            if (adresse.isBlank()) adresse = s.optString("adresse").trim()
            if (cp.isBlank()) cp = s.optString("codepostal").trim()
        }
        if (adresse.isBlank()) adresse = o.optString("adresse").trim()
        if (cp.isBlank()) cp = o.optString("codepostal").trim()

        val right = listOf(adresse, cp).filter { it.isNotBlank() }.joinToString(" ")
        val parts = listOf(domaine, right).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        var line = parts.joinToString(", ")
        val heure = weddingTime(o)
        if (heure.isNotBlank()) line += " (à $heure)"
        return line
    }

    /** Heure de cérémonie (champ exact incertain : on tente les noms plausibles). */
    private fun weddingTime(o: JSONObject): String {
        val viaMariage = o.optJSONObject("mariage")?.optString("heure").orEmpty()
        return viaMariage.ifBlank { o.optString("heure", "") }.trim()
    }

    /**
     * Horaires sur place depuis `planning` : heure d'arrivée (plus tôt) - heure de
     * fin la plus tardive (finprestas), avec le nombre de prestations.
     * Ex. "Sur place : 14h40 - 18h00 (2)".
     */
    private fun scheduleLine(o: JSONObject): String {
        val p = o.optJSONObject("planning") ?: return ""
        var minM = Int.MAX_VALUE
        var maxM = Int.MIN_VALUE
        val invitees = p.optJSONArray("invitees")
        if (invitees != null) {
            for (i in 0 until invitees.length()) {
                val row = invitees.optJSONArray(i) ?: continue
                for (j in 0 until row.length()) {
                    val m = parseHm(row.optString(j, "")) ?: continue
                    if (m < minM) minM = m
                    if (m > maxM) maxM = m
                }
            }
        }
        val arrival = if (minM != Int.MAX_VALUE) formatHm(minM) else ""
        val end = p.optString("finprestas").trim()
            .ifBlank { if (maxM != Int.MIN_VALUE) formatHm(maxM) else "" }
        if (arrival.isBlank() && end.isBlank()) return ""

        val range = listOf(arrival, end).filter { it.isNotBlank() }.joinToString(" - ")
        val count = p.optJSONArray("planningprestas")?.length() ?: 0
        return if (count > 0) "Sur place : $range ($count)" else "Sur place : $range"
    }

    /** "14h40" / "10h" / "9h05" -> minutes depuis minuit, ou null si non parsable. */
    private fun parseHm(s: String): Int? {
        val t = s.trim().lowercase()
        if (!t.contains('h')) return null
        val parts = t.split('h')
        val h = parts[0].toIntOrNull() ?: return null
        val m = if (parts.size > 1 && parts[1].isNotBlank()) (parts[1].toIntOrNull() ?: 0) else 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun formatHm(min: Int): String = String.format("%02dh%02d", min / 60, min % 60)

    /**
     * Reste à payer = "Moi" (somme des prestations du devis hors frais de
     * déplacement) - somme des soldes de factures.
     */
    private fun resteLine(o: JSONObject): String {
        val prestas = o.optJSONObject("devis")?.optJSONArray("prestas") ?: return ""
        var moi = 0.0
        for (i in 0 until prestas.length()) {
            val pr = prestas.optJSONObject(i) ?: continue
            if (pr.optBoolean("kilorly", false)) continue // exclut les frais de déplacement
            val qte = asDouble(pr.opt("qte")) ?: continue
            val prix = asDouble(pr.opt("prix")) ?: continue
            val reduc = asDouble(pr.opt("reduc")) ?: 0.0
            moi += qte * prix - reduc
        }
        if (moi <= 0.0) return ""

        var paye = 0.0
        val factures = o.optJSONArray("factures")
        if (factures != null) {
            for (i in 0 until factures.length()) {
                paye += asDouble(factures.optJSONObject(i)?.opt("solde")) ?: 0.0
            }
        }
        return "Reste à payer : ${formatEuro(moi - paye)}"
    }

    /** Nombre depuis un Number ou une chaîne ("744.54", "9,5"), sinon null. */
    private fun asDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    private fun formatEuro(v: Double): String {
        val rounded = Math.round(v * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) "${rounded.toLong()} €"
        else String.format("%.2f €", rounded)
    }

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
