package com.cloechaudron.planning

import java.util.Calendar
import java.util.Locale
import kotlin.math.floor

/**
 * Utilitaires de dates « jj/mm/aaaa » et de montants — ports des `date.utils` /
 * `money.utils` du nouveau IntraCCB2. Purs, sans état.
 */
object FrDate {

    val MONTHS = arrayOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    /** Libellés du sélecteur (capitalisés, comme l'app). */
    val MONTHS_CAP = MONTHS.map { capitalize(it) }

    private val WEEKDAYS = arrayOf(
        "dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
    )

    fun isValid(s: String?): Boolean {
        if (s == null) return false
        val parts = s.split("/")
        if (parts.size != 3) return false
        val d = parts[0].trim().toIntOrNull() ?: return false
        val m = parts[1].trim().toIntOrNull() ?: return false
        val y = parts[2].trim().toIntOrNull() ?: return false
        return y in 2000..2100 && m in 1..12 && d in 1..31
    }

    /** « jj/mm/aaaa » → millis (minuit local), ou null si invalide. */
    fun millis(s: String?): Long? {
        if (!isValid(s)) return null
        val (d, m, y) = s!!.split("/").map { it.trim().toInt() }
        return dayMillis(y, m - 1, d)
    }

    fun dayMillis(year: Int, month0: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month0, day, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun yearOf(s: String): Int? = s.split("/").getOrNull(2)?.trim()?.toIntOrNull()
    fun monthOf(s: String): Int? = s.split("/").getOrNull(1)?.trim()?.toIntOrNull()

    fun isPast(s: String): Boolean {
        val m = millis(s) ?: return false
        return m < startOfToday()
    }

    fun isToday(s: String): Boolean = millis(s) == startOfToday()

    /** Comparateur croissant pour trier des dates « jj/mm/aaaa ». */
    fun compare(a: String, b: String): Int {
        val da = millis(a) ?: 0L
        val db = millis(b) ?: 0L
        return da.compareTo(db)
    }

    /** « jj/mm/aaaa » → « Lundi 15 Juin 2026 » (ou la chaîne brute si invalide). */
    fun longDate(s: String): String {
        val m = millis(s) ?: return s
        val c = Calendar.getInstance().apply { timeInMillis = m }
        val dow = capitalize(WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1])
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = capitalize(MONTHS[c.get(Calendar.MONTH)])
        val year = c.get(Calendar.YEAR)
        return "$dow $day $month $year"
    }

    /** « Lundi 15 Juin » (sans l'année) pour les cartes compactes. */
    fun longDateNoYear(s: String): String {
        val m = millis(s) ?: return s
        val c = Calendar.getInstance().apply { timeInMillis = m }
        val dow = capitalize(WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1])
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = capitalize(MONTHS[c.get(Calendar.MONTH)])
        return "$dow $day $month"
    }

    private fun capitalize(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRENCH) else it.toString() }
}

/**
 * Mise en forme des montants — port de `money.utils` :
 *  - entier < 100 → deux décimales virgule (« 80,00 ») ;
 *  - entier ≥ 100 → entier brut (« 450 ») ;
 *  - décimal      → deux décimales virgule (« 12,50 »).
 */
object Money {

    fun formatAmount(value: Double): String {
        if (value.isNaN()) return ""
        if (value == floor(value) && !value.isInfinite()) {
            val n = value.toLong()
            return if (n < 100) twoDecimals(value) else n.toString()
        }
        return twoDecimals(value)
    }

    fun euro(value: Double): String = formatAmount(value) + "€"

    /** Comme l'interpolation `${total}` de JS : pas de « .0 » superflu. */
    fun js(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

    private fun twoDecimals(value: Double): String =
        String.format(Locale.US, "%.2f", value).replace('.', ',')
}
