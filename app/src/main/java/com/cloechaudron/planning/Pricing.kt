package com.cloechaudron.planning

import kotlin.math.floor

/**
 * Moteur de prix — port fidèle de `PricingService` (lecture seule). Source unique
 * de toute la logique chiffrée affichée (récap des fiches, reste à payer).
 *
 * Différences clés avec l'ancien widget, conservées à l'identique du nouveau
 * IntraCCB2 :
 *  - frais au km (`kilorly`) facturés ALLER/RETOUR : `qte × 2 × prix` ;
 *  - une ligne « renfort » revient au prestataire et n'entre JAMAIS dans MON total ;
 *  - `factureSold` = `realsold` ‖ `solde` ‖ somme des lignes.
 */
object Pricing {

    /** Total d'une ligne (quantité × prix, frais au km, réduction, arrondi). */
    fun lineTotal(p: Presta): Double {
        if (p.qte == null) return 0.0
        val qte = p.qte
        val prix = p.prix ?: 0.0
        var total = prix * qte
        if (p.kilorly) total = qte * 2 * prix
        if (p.reduc != null && p.reduc != 0.0) total -= total * p.reduc / 100
        if (isInteger(prix) || p.kilorly) total = floor(total)
        return total
    }

    /** Total d'un ensemble de prestations (lignes à quantité positive). */
    fun total(prestas: List<Presta>): Double {
        var sum = 0.0
        for (p in prestas) if (p.qte != null && p.qte > 0) sum += lineTotal(p)
        return floor(sum)
    }

    /** Montant encaissé pour une facture (solde réel, solde, ou somme des lignes). */
    fun factureSold(f: Facture): Double {
        if (f.realsold != null && f.realsold != 0.0) return f.realsold
        if (f.solde != null && f.solde != 0.0) return f.solde
        var sum = 0.0
        for (p in f.prestas) sum += lineTotal(p)
        return floor(sum)
    }

    /** Total déjà payé sur une journée (toutes factures). */
    fun paid(j: Journee): Double {
        var sum = 0.0
        for (f in j.factures) {
            if (f.solde != null && f.solde != 0.0) sum += factureSold(f)
            else for (p in f.prestas) sum += lineTotal(p)
        }
        return sum
    }

    // --- Modèle « moi uniquement » -------------------------------------------

    /** Mes lignes (hors prestataire/renfort). */
    fun myPrestas(prestas: List<Presta>): List<Presta> = prestas.filter { !it.isProvider }

    /** Au moins une prestation est confiée à un prestataire. */
    fun hasProvider(prestas: List<Presta>): Boolean = prestas.any { it.isProvider }

    /** Mon total = somme de MES lignes uniquement. */
    fun myTotal(prestas: List<Presta>): Double = total(myPrestas(prestas))

    /** Montant informatif revenant au prestataire (jamais dans mes calculs). */
    fun providerTotal(prestas: List<Presta>): Double = total(prestas.filter { it.isProvider })

    /** Mon total pour la journée = total de mes lignes du dernier devis. */
    fun myShare(j: Journee): Double = myTotal(j.devis?.prestas ?: emptyList())

    /** Reste à payer d'une journée = mon total − déjà payé. */
    fun reste(j: Journee): Double = myShare(j) - paid(j)

    // --- Récapitulatif (fiche) ------------------------------------------------

    /** Lignes du récap : uniquement MES prestations. */
    fun recapLines(j: Journee): List<Presta> = myPrestas(j.devis?.prestas ?: emptyList())

    /** Libellé du total d'une ligne : « », « Offert » ou « 120€ ». */
    fun lineLabel(p: Presta): String {
        if (p.qte == null) return ""
        val total = lineTotal(p)
        if (total == 0.0) return "Offert"
        return "${Money.js(total)}${if (total < 100) ",00" else ""}€"
    }

    private fun isInteger(d: Double): Boolean = !d.isInfinite() && d == floor(d)
}
