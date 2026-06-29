package com.cloechaudron.planning

import kotlin.math.floor

/** Une facture du mois, enrichie de la journée (pour ouvrir la fiche). */
data class FactureLigne(
    val nom: String,
    val creation: String,
    val date: String,
    val montant: Double,
    val id: Int?,
)

/** Compteur d'un statut : encore à faire / déjà terminés. */
data class StatutCount(val still: Int, val over: Int)

/** Série annuelle pour le graphe (revenu mensuel + cumul). */
data class YearSeries(val year: Int, val monthly: DoubleArray, val cumulative: DoubleArray)

/**
 * Indicateurs du tableau de bord — port fidèle de `StatsService`. Pur et sans
 * état ; `month == null` signifie « toute l'année », sinon 1–12. Les méthodes
 * prennent la liste des entrées du bilan (cf. PlanningRepository.statsEntries).
 */
object Stats {

    /** Abattement appliqué au net (par défaut 24 %, comme l'admin de l'app). */
    const val TAX_RATE = 0.24

    private fun inPeriod(dateStr: String, year: Int, month: Int?): Boolean =
        FrDate.yearOf(dateStr) == year && (month == null || FrDate.monthOf(dateStr) == month)

    // --- Heures travaillées ---------------------------------------------------

    private fun planningPrestasTime(prestas: List<PlanningPresta>): Double {
        var time = 0.0
        for (p in prestas) if (p.time != null && p.time != 0 && p.artisteIndex == 0) time += p.time
        return time
    }

    private fun prestasTime(prestas: List<Presta>?): Double {
        var time = 0.0
        for (p in prestas ?: emptyList()) {
            if (p.time != null && p.time != 0 && p.qte != null && p.qte != 0.0) time += p.time * p.qte
        }
        return time
    }

    /** Somme des lignes d'une facture (renforts inclus, comme `prestasPrice(..,false)`). */
    private fun facturePrestasAll(prestas: List<Presta>): Double {
        var price = 0.0
        for (p in prestas) price += Pricing.lineTotal(p)
        return price
    }

    /** Minutes travaillées (essai 120, autre 240, mariage depuis planning/devis). */
    fun hoursWorked(
        journees: List<Journee>,
        year: Int,
        month: Int?,
        fromNow: Boolean,
        untilNow: Boolean,
    ): Double {
        val today = FrDate.startOfToday()
        var data = journees.filter { inPeriod(it.date, year, month) && it.statut != Statut.ANNULE }
        if (fromNow) data = data.filter { onOrAfter(it.date, today) }
        else if (untilNow) data = data.filter { before(it.date, today) }

        var time = 0.0
        for (day in data) {
            when (day.statut) {
                Statut.ESSAI -> time += 120
                Statut.AUTRE -> time += 240
                Statut.RESERVE -> {
                    val pp = day.planning?.prestas
                    time += if (!pp.isNullOrEmpty()) planningPrestasTime(pp) else prestasTime(day.devis?.prestas)
                }
                else -> {}
            }
        }
        return time
    }

    /** Tarif horaire moyen (€/h) sur la période. */
    fun perHour(journees: List<Journee>, year: Int, month: Int?, fromNow: Boolean, untilNow: Boolean): Double {
        val hours = (hoursWorked(journees, year, month, fromNow, untilNow) / 60).toLong()
        if (hours <= 0) return 0.0
        val base = if (fromNow) notPaid(journees, year, month) else alreadyPaid(journees, year, month)
        return (base / hours).toLong().toDouble()
    }

    // --- Part journalière -----------------------------------------------------

    private fun mineThisDay(j: Journee): Double = Pricing.myShare(j)

    private fun alreadyPaidThisDay(j: Journee): Double {
        var total = 0.0
        for (f in j.factures) {
            if (f.solde != null && f.solde != 0.0) {
                total += if (f.realsold != null && f.realsold != 0.0) f.realsold else f.solde
            } else {
                total += facturePrestasAll(f.prestas)
                if (f.realsold != null && f.realsold != 0.0) total = f.realsold
            }
        }
        return floor(total)
    }

    private fun hasQuote(j: Journee): Boolean =
        (j.devis?.prestas?.size ?: 0) > 0 || (j.planning?.prestas?.size ?: 0) > 0

    // --- Agrégats -------------------------------------------------------------

    /** Déjà encaissé sur la période (par date de facture). */
    fun alreadyPaid(journees: List<Journee>, year: Int, month: Int?): Double {
        var total = 0.0
        for (j in journees) for (f in j.factures) {
            val creation = f.creation ?: continue
            if (!inPeriod(creation, year, month)) continue
            if (f.solde != null && f.solde != 0.0) {
                total += if (f.realsold != null && f.realsold != 0.0) f.realsold else f.solde
            }
        }
        return trunc(total)
    }

    /** Reste à encaisser sur la période (par date de journée). */
    fun notPaid(journees: List<Journee>, year: Int, month: Int?, withDemands: Boolean = false): Double {
        var data = journees.filter {
            inPeriod(it.date, year, month) && it.etape != ETAPE_TERMINE &&
                it.statut != Statut.ANNULE && hasQuote(it)
        }
        if (!withDemands) data = data.filter { it.statut != Statut.DEMANDE }
        var total = 0.0
        for (j in data) total += mineThisDay(j) - alreadyPaidThisDay(j)
        return trunc(total)
    }

    /** Pourboires en argent liquide sur la période (non taxés). */
    fun cash(journees: List<Journee>, year: Int, month: Int?): Double {
        var total = 0.0
        for (j in journees) if (j.argentLiquide != null && inPeriod(j.date, year, month)) total += j.argentLiquide
        return trunc(total)
    }

    /** Estimation (déjà payé + à venir, demandes incluses). */
    fun estimate(journees: List<Journee>, year: Int, month: Int?): Double =
        alreadyPaid(journees, year, month) + notPaid(journees, year, month, true)

    /** Total (déjà payé + reste à encaisser, hors demandes). */
    fun totalRevenue(journees: List<Journee>, year: Int, month: Int?): Double =
        alreadyPaid(journees, year, month) + notPaid(journees, year, month)

    /** Montant net après abattement. */
    fun net(amount: Double): Double = trunc(amount - amount * TAX_RATE)

    // --- Factures -------------------------------------------------------------

    /** Factures encaissées sur la période, la plus récente en haut. */
    fun monthFactures(journees: List<Journee>, year: Int, month: Int?): List<FactureLigne> {
        val lignes = ArrayList<FactureLigne>()
        for (j in journees) for (f in j.factures) {
            val creation = f.creation ?: continue
            if (!inPeriod(creation, year, month)) continue
            lignes.add(FactureLigne(j.client.nom ?: "", creation, j.date, Pricing.factureSold(f), j.id))
        }
        return lignes.sortedByDescending { FrDate.millis(it.creation) ?: 0L }
    }

    /** Journées avec un reste à encaisser sur la période (hors demandes et clôturées). */
    fun monthFacturesMissing(journees: List<Journee>, year: Int, month: Int?): List<FactureLigne> {
        val lignes = ArrayList<FactureLigne>()
        val data = journees.filter {
            inPeriod(it.date, year, month) && it.etape != ETAPE_TERMINE &&
                it.statut != Statut.DEMANDE && it.statut != Statut.ANNULE
        }
        for (j in data) {
            if (!hasQuote(j)) continue
            val reste = mineThisDay(j) - alreadyPaidThisDay(j)
            if (reste > 0) lignes.add(FactureLigne(j.client.nom ?: "", j.date, j.date, reste, j.id))
        }
        return lignes.sortedBy { FrDate.millis(it.creation) ?: 0L }
    }

    // --- Compteurs ------------------------------------------------------------

    fun statutCount(journees: List<Journee>, statut: Statut, year: Int, month: Int?): StatutCount {
        val data = journees.filter { inPeriod(it.date, year, month) && it.statut == statut }
        return StatutCount(
            still = data.count { it.etape != ETAPE_TERMINE },
            over = data.count { it.etape == ETAPE_TERMINE },
        )
    }

    /** Nombre de journées à une étape donnée (hors perso/annulé). */
    fun etapeCount(journees: List<Journee>, etape: Int, year: Int, month: Int?): Int =
        journees.count {
            inPeriod(it.date, year, month) && it.etape == etape &&
                it.statut != Statut.PERSO && it.statut != Statut.PERSO_FULL && it.statut != Statut.ANNULE
        }

    // --- Série annuelle (graphe) ----------------------------------------------

    fun yearSeries(journees: List<Journee>): List<YearSeries> {
        val byYear = HashMap<Int, DoubleArray>()
        for (j in journees) for (f in j.factures) {
            val creation = f.creation ?: continue
            val year = FrDate.yearOf(creation) ?: continue
            val month = FrDate.monthOf(creation) ?: continue
            val arr = byYear.getOrPut(year) { DoubleArray(12) }
            if (month in 1..12) arr[month - 1] += Pricing.factureSold(f)
        }
        return byYear.entries.sortedBy { it.key }.map { (year, monthly) ->
            val cumulative = DoubleArray(12)
            var sum = 0.0
            for (i in 0 until 12) {
                sum += monthly[i]
                cumulative[i] = sum
            }
            YearSeries(year, monthly, cumulative)
        }
    }

    // --- Helpers --------------------------------------------------------------

    /** Tronque vers zéro, comme `Math.trunc`. */
    private fun trunc(d: Double): Double = d.toLong().toDouble()

    private fun onOrAfter(dateStr: String, today: Long): Boolean {
        val m = FrDate.millis(dateStr) ?: return false
        return m >= today
    }

    private fun before(dateStr: String, today: Long): Boolean {
        val m = FrDate.millis(dateStr) ?: return false
        return m < today
    }
}
