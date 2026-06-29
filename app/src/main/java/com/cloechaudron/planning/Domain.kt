package com.cloechaudron.planning

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modèle de domaine + parseur JSON — port fidèle (lecture seule) du *nouveau*
 * IntraCCB2 (modèles + mapper Angular). Tout ce qui concerne l'édition et les
 * PDF (devis/facture/planning) est volontairement laissé de côté : on ne garde
 * que ce qui sert à AFFICHER le planning, les fiches et le bilan.
 *
 * Le backend reste l'API PHP historique : champs `number | string`, coordonnées
 * client « à plat », tableaux positionnels du planning, liste des avenants rangée
 * dans `devis.versions`. Cette connaissance est concentrée ici (équivalent des
 * `dto/` + `mappers/` côté Angular).
 */

/** Étape clôturée (event terminé). Reprend la valeur historique. */
const val ETAPE_TERMINE = 999

/** Nature d'une journée du calendrier (valeurs identiques à la base). */
enum class Statut(val code: String) {
    DEMANDE("demande"),
    RESERVE("reserve"),
    ESSAI("essai"),
    AUTRE("autre"),
    PERSO("perso"),
    PERSO_FULL("persofull"),
    ANNULE("annule");

    val isPerso: Boolean get() = this == PERSO || this == PERSO_FULL

    companion object {
        fun from(code: String?): Statut =
            values().firstOrNull { it.code == code } ?: DEMANDE
    }
}

data class Client(
    val nom: String? = null,
    val adresse: String? = null,
    val codepostal: String? = null,
    val tel: String? = null,
    val mail: String? = null,
)

data class Essai(
    val date: String? = null,
    val heure: String? = null,
    val lieu: String? = null,
)

data class Mariage(
    val domaine: String? = null,
    val adresse: String? = null,
    val codepostal: String? = null,
    val ceremonie: String? = null,
)

/**
 * Ligne de prestation. `qte == null` représente la quantité « ? » de l'app
 * (placeholder catalogue) : elle ne compte pas dans les totaux.
 */
data class Presta(
    val nom: String = "",
    val qte: Double?,
    val prix: Double? = null,
    val reduc: Double? = null,
    val kilorly: Boolean = false,
    val bride: Boolean = false,
    val renfort: Boolean = false,
    val time: Int? = null,
) {
    /** Confiée à un prestataire/renfort (drapeau ou intitulé). */
    val isProvider: Boolean get() = renfort || nom.contains("renfort")

    /** Quantité telle qu'affichée dans le récap (« ? » si inconnue). */
    fun qteLabel(): String = when {
        qte == null -> "?"
        qte == kotlin.math.floor(qte) -> qte.toLong().toString()
        else -> qte.toString()
    }
}

data class Devis(
    val numero: Int? = null,
    val annee: String? = null,
    val creation: String? = null,
    val echeance: String? = null,
    val prestas: List<Presta> = emptyList(),
)

data class Facture(
    val numero: Int? = null,
    val annee: String? = null,
    val creation: String? = null,
    val type: String? = null,
    val prestas: List<Presta> = emptyList(),
    val solde: Double? = null,
    val realsold: Double? = null,
)

/** Prestation du planning : on ne retient que la durée et l'artiste (heures travaillées). */
data class PlanningPresta(val time: Int?, val artisteIndex: Int)

/**
 * Planning du jour-J — uniquement ce qui sert à LIRE les horaires sur place :
 * les lignes positionnelles `invitees` (heures), l'heure de fin, le lieu, et les
 * prestations (pour la durée travaillée du bilan).
 */
data class Planning(
    val date: String? = null,
    val domaine: String? = null,
    val adresse: String? = null,
    val codepostal: String? = null,
    val ceremonie: String? = null,
    val finPrestas: String? = null,
    /** Tableaux positionnels historiques (1 ligne = 1 prestation, cellules = heures). */
    val invitees: List<List<String>> = emptyList(),
    val prestas: List<PlanningPresta> = emptyList(),
)

/** Une journée du calendrier : l'agrégat central du domaine. */
data class Journee(
    val id: Int? = null,
    val date: String,
    val statut: Statut,
    val etape: Int,
    val client: Client = Client(),
    val essai: Essai = Essai(),
    val mariage: Mariage = Mariage(),
    val devisList: List<Devis> = emptyList(),
    val factures: List<Facture> = emptyList(),
    val planning: Planning? = null,
    val mariagenet: String? = null,
    val argentLiquide: Double? = null,
    val avis: Boolean = false,
) {
    /** Dernier devis = base de tous les calculs (récap, reste à payer). */
    val devis: Devis? get() = devisList.lastOrNull()

    val isEssai: Boolean get() = statut == Statut.ESSAI
    val isTermine: Boolean get() = etape == ETAPE_TERMINE
}

/**
 * Parseur JSON → domaine. Équivalent Kotlin de `journeeToDomain`, limité à la
 * lecture. À appeler depuis un thread worker (aucun accès réseau ici).
 */
object JourneeParser {

    fun parse(json: String): List<Journee> {
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Journee>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(journee(o))
        }
        return out
    }

    private fun journee(o: JSONObject): Journee {
        val devisList = devisList(o.optJSONObject("devis"))
        return Journee(
            id = if (o.has("id")) o.optInt("id") else null,
            date = o.optString("date", ""),
            statut = Statut.from(o.optString("statut", "")),
            etape = o.optInt("etape", 0),
            client = Client(
                nom = optStr(o, "nom"),
                adresse = optStr(o, "adresse"),
                codepostal = optStr(o, "codepostal"),
                tel = optStr(o, "tel"),
                mail = optStr(o, "mail"),
            ),
            essai = essai(o.optJSONObject("essai")),
            mariage = mariage(o.optJSONObject("mariage")),
            devisList = devisList,
            factures = factures(o.optJSONArray("factures")),
            planning = o.optJSONObject("planning")?.let { planning(it) },
            mariagenet = optStr(o, "mariagenet"),
            argentLiquide = asDouble(o.opt("argentliquide")),
            avis = asBool(o.opt("avis")),
        )
    }

    private fun essai(o: JSONObject?): Essai =
        Essai(optStr(o, "date"), optStr(o, "heure"), optStr(o, "lieu"))

    private fun mariage(o: JSONObject?): Mariage = Mariage(
        domaine = optStr(o, "domaine"),
        adresse = optStr(o, "adresse"),
        codepostal = optStr(o, "codepostal"),
        ceremonie = optStr(o, "ceremonie"),
    )

    /**
     * Liste des devis : la liste complète (initial + avenants) est rangée dans
     * `versions` au sein du dernier devis. À défaut (données anciennes), le devis
     * unique devient une liste à un élément. Liste vide si aucun devis réel.
     */
    private fun devisList(o: JSONObject?): List<Devis> {
        if (o == null) return emptyList()
        val versions = o.optJSONArray("versions")
        if (versions != null && versions.length() > 0) {
            val list = ArrayList<Devis>(versions.length())
            for (i in 0 until versions.length()) {
                versions.optJSONObject(i)?.let { list.add(devis(it)) }
            }
            return list
        }
        val hasCreation = !optStr(o, "creation").isNullOrBlank()
        val hasPrestas = (o.optJSONArray("prestas")?.length() ?: 0) > 0
        return if (hasCreation || hasPrestas) listOf(devis(o)) else emptyList()
    }

    private fun devis(o: JSONObject): Devis = Devis(
        numero = asDouble(o.opt("numero"))?.toInt(),
        annee = optStr(o, "annee"),
        creation = optStr(o, "creation"),
        echeance = optStr(o, "echeance"),
        prestas = prestas(o.optJSONArray("prestas")),
    )

    private fun factures(arr: JSONArray?): List<Facture> {
        if (arr == null) return emptyList()
        val list = ArrayList<Facture>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                Facture(
                    numero = asDouble(o.opt("numero"))?.toInt(),
                    annee = optStr(o, "annee"),
                    creation = optStr(o, "creation"),
                    type = optStr(o, "type"),
                    prestas = prestas(o.optJSONArray("prestas")),
                    solde = asDouble(o.opt("solde")),
                    realsold = asDouble(o.opt("realsold")),
                )
            )
        }
        return list
    }

    private fun prestas(arr: JSONArray?): List<Presta> {
        if (arr == null) return emptyList()
        val list = ArrayList<Presta>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                Presta(
                    nom = o.optString("nom", ""),
                    qte = asQte(o.opt("qte")),
                    prix = asDouble(o.opt("prix")),
                    reduc = asDouble(o.opt("reduc")),
                    kilorly = o.optBoolean("kilorly", false),
                    bride = o.optBoolean("bride", false),
                    renfort = asBool(o.opt("renfort")),
                    time = if (o.has("time")) o.optInt("time") else null,
                )
            )
        }
        return list
    }

    private fun planning(o: JSONObject): Planning {
        val invitees = ArrayList<List<String>>()
        o.optJSONArray("invitees")?.let { rows ->
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val cells = ArrayList<String>(row.length())
                for (j in 0 until row.length()) cells.add(row.optString(j, ""))
                invitees.add(cells)
            }
        }
        val prestas = ArrayList<PlanningPresta>()
        o.optJSONArray("planningprestas")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                prestas.add(
                    PlanningPresta(
                        time = if (p.has("time")) p.optInt("time") else null,
                        artisteIndex = asDouble(p.opt("presta"))?.toInt() ?: 0,
                    )
                )
            }
        }
        return Planning(
            date = optStr(o, "date"),
            domaine = optStr(o, "domaine"),
            adresse = optStr(o, "adresse"),
            codepostal = optStr(o, "codepostal"),
            ceremonie = optStr(o, "ceremonie"),
            finPrestas = optStr(o, "finprestas"),
            invitees = invitees,
            prestas = prestas,
        )
    }

    // --- Coercition (équivalent des helpers du mapper) ------------------------

    private fun optStr(o: JSONObject?, key: String): String? {
        if (o == null || o.isNull(key)) return null
        val v = o.optString(key, "")
        return v.ifBlank { null }
    }

    /** Quantité : « ? » → null ; sinon nombre (0 si illisible), comme `toQuantity`. */
    private fun asQte(v: Any?): Double? = if (v is String && v.trim() == "?") null else (asDouble(v) ?: 0.0)

    /** Nombre depuis Number ou chaîne (« 744,54 », « 12.5 »), sinon null. */
    fun asDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> {
            val cleaned = v.replace(Regex("[^0-9.,-]"), "").replace(',', '.')
            cleaned.toDoubleOrNull()
        }
        else -> null
    }

    /** Booléen tolérant : `true`/non vide (sauf « false ») → vrai (comme `toBool`). */
    private fun asBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v.isNotBlank() && v != "false"
        else -> false
    }
}
