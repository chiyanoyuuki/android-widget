package com.cloechaudron.planning

/**
 * Carte « événement » en lecture seule, dérivée d'une [Journee]. Regroupe ce
 * qu'on affiche dans le widget « prochain événement » et la fiche détail :
 * type, client, lieu (+ cérémonie), horaires du jour-J et reste à payer.
 *
 * Reprend la logique d'`initData` / des sélecteurs de l'app : un essai
 * (reconstruit à la date d'essai d'un mariage) affiche son lieu et son heure ;
 * un mariage affiche l'adresse, les horaires sur place et le reste à payer.
 */
data class EventCard(
    val journee: Journee,
    val date: String,
    val millis: Long,
    /** Heure d'arrivée (tri intra-jour), Int.MAX_VALUE si inconnue. */
    val startMin: Int,
    /** Heure de fin (dépassement « aujourd'hui »), ou null. */
    val departMin: Int?,
    val dateLabel: String,
    val typeLabel: String,
    val hue: Int,
    val name: String,
    /** Lieu : « Domaine, adresse cp (à 15h30) » ou lieu de l'essai. */
    val lieuLine: String,
    /** Horaires sur place : « Sur place : 14h40 - 18h00 (2) ». */
    val horairesLine: String,
    /** « Reste à payer : 540€ » (vide si pas de devis chiffré). */
    val resteLine: String,
    val phone: String,
    val email: String,
    val mailSubject: String,
) {
    val hasContact: Boolean get() = phone.isNotBlank() || email.isNotBlank()
}

object EventCards {

    fun build(j: Journee): EventCard {
        val millis = FrDate.millis(j.date) ?: 0L
        val dateLabel = FrDate.longDate(j.date)
        return if (j.isEssai) essaiCard(j, millis, dateLabel) else weddingCard(j, millis, dateLabel)
    }

    private fun essaiCard(j: Journee, millis: Long, dateLabel: String): EventCard {
        val lieu = j.essai.lieu?.trim().orEmpty()
        val heure = j.essai.heure?.trim().orEmpty()
        val loc = when {
            lieu.isNotBlank() && heure.isNotBlank() -> "$lieu (à $heure)"
            lieu.isNotBlank() -> lieu
            heure.isNotBlank() -> "à $heure"
            else -> ""
        }
        val hm = parseHm(heure)
        return EventCard(
            journee = j,
            date = j.date,
            millis = millis,
            startMin = hm ?: Int.MAX_VALUE,
            departMin = hm,
            dateLabel = dateLabel,
            typeLabel = "Essai",
            hue = Palette.ESSAI,
            name = j.client.nom?.trim().orEmpty(),
            lieuLine = loc,
            horairesLine = "",
            resteLine = "",
            phone = j.client.tel?.trim().orEmpty(),
            email = j.client.mail?.trim().orEmpty(),
            mailSubject = "Essai - ${j.essai.date.orEmpty()}",
        )
    }

    private fun weddingCard(j: Journee, millis: Long, dateLabel: String): EventCard {
        val type = Statuts.typeLabel(j.statut, j.etape)
        val reste = if (Pricing.myShare(j) > 0) "Reste à payer : ${Money.euro(Pricing.reste(j))}" else ""
        return EventCard(
            journee = j,
            date = j.date,
            millis = millis,
            startMin = arrivalMinutes(j.planning),
            departMin = departureMinutes(j.planning),
            dateLabel = dateLabel,
            typeLabel = type,
            hue = Statuts.hue(j.statut, j.etape),
            name = j.client.nom?.trim().orEmpty(),
            lieuLine = addressLine(j),
            horairesLine = scheduleLine(j.planning),
            resteLine = reste,
            phone = j.client.tel?.trim().orEmpty(),
            email = j.client.mail?.trim().orEmpty(),
            mailSubject = "$type - ${j.date}",
        )
    }

    /** Adresse du mariage : « Domaine, adresse cp (à 15h30) ». Mariage prioritaire, sinon planning. */
    fun addressLine(j: Journee): String {
        val domaine = firstNonBlank(j.mariage.domaine, j.planning?.domaine)
        val adresse = firstNonBlank(j.mariage.adresse, j.planning?.adresse)
        val cp = firstNonBlank(j.mariage.codepostal, j.planning?.codepostal)
        val right = listOf(adresse, cp).filter { it.isNotBlank() }.joinToString(" ")
        val parts = listOf(domaine, right).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        var line = parts.joinToString(", ")
        val ceremonie = firstNonBlank(j.mariage.ceremonie, j.planning?.ceremonie)
        if (ceremonie.isNotBlank()) line += " (à $ceremonie)"
        return line
    }

    /**
     * Horaires sur place depuis le planning : arrivée la plus tôt → fin la plus
     * tardive, avec le nombre de prestations. Port de `scheduleLine` du widget.
     */
    fun scheduleLine(p: Planning?): String {
        if (p == null) return ""
        var minM = Int.MAX_VALUE
        var maxM = Int.MIN_VALUE
        for (row in p.invitees) for (cell in row) {
            val m = parseHm(cell) ?: continue
            if (m < minM) minM = m
            if (m > maxM) maxM = m
        }
        val arrival = if (minM != Int.MAX_VALUE) formatHm(minM) else ""
        val end = (p.finPrestas?.trim().takeUnless { it.isNullOrBlank() })
            ?: if (maxM != Int.MIN_VALUE) formatHm(maxM) else ""
        if (arrival.isBlank() && end.isBlank()) return ""
        val range = listOf(arrival, end).filter { it.isNotBlank() }.joinToString(" - ")
        val count = p.prestas.size
        return if (count > 0) "Sur place : $range ($count)" else "Sur place : $range"
    }

    /** Heure d'arrivée (plus tôt) en minutes, Int.MAX_VALUE si inconnue. */
    private fun arrivalMinutes(p: Planning?): Int {
        if (p == null) return Int.MAX_VALUE
        var minM = Int.MAX_VALUE
        for (row in p.invitees) for (cell in row) {
            val m = parseHm(cell) ?: continue
            if (m < minM) minM = m
        }
        return minM
    }

    /** Heure de fin (finprestas, ou plus tard des invitees) en minutes, ou null. */
    private fun departureMinutes(p: Planning?): Int? {
        if (p == null) return null
        parseHm(p.finPrestas?.trim().orEmpty())?.let { return it }
        var maxM = Int.MIN_VALUE
        for (row in p.invitees) for (cell in row) {
            val m = parseHm(cell) ?: continue
            if (m > maxM) maxM = m
        }
        return if (maxM == Int.MIN_VALUE) null else maxM
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

    /** « 14h40 » / « 10h » / « 9h05 » → minutes depuis minuit, ou null. */
    fun parseHm(s: String): Int? {
        val t = s.trim().lowercase()
        if (!t.contains('h')) return null
        val parts = t.split('h')
        val h = parts[0].toIntOrNull() ?: return null
        val m = if (parts.size > 1 && parts[1].isNotBlank()) (parts[1].toIntOrNull() ?: 0) else 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun formatHm(min: Int): String = String.format("%02dh%02d", min / 60, min % 60)
}
