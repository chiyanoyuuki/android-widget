package com.cloechaudron.planning

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Charte du *nouveau* IntraCCB2 (couleurs harmonisées autour du mauve) + libellés
 * de statut. Source unique reprise des `_variables.scss` / `_statut.scss` et de
 * `statut-ui.ts`. C'est ici que se voit la différence avec l'ancien widget : les
 * teintes ont changé (réservé bleu-gris, perso vert, terminé gris…).
 */
object Palette {
    // Marque
    val BRAND = Color.parseColor("#aa82ba")
    val BRAND_STRONG = Color.parseColor("#87528e")
    val BRAND_DEEP = Color.parseColor("#30173d")
    val BRAND_SOFT = Color.parseColor("#ece4f0")

    // Surfaces & texte
    val BG = Color.parseColor("#f5f1f8")
    val SURFACE = Color.parseColor("#ffffff")
    val SURFACE_2 = Color.parseColor("#faf7fc")
    val TEXT = Color.parseColor("#2c2431")
    val TEXT_MUTED = Color.parseColor("#7c7184")
    val BORDER = Color.parseColor("#e8dfee")
    val BORDER_STRONG = Color.parseColor("#d8cbe0")

    // Feedback
    val DANGER = Color.parseColor("#b3261e")
    val SUCCESS = Color.parseColor("#3f7d52")
    val WARNING = Color.parseColor("#b0822f")

    // Statuts (harmonisés)
    val RESERVE = Color.parseColor("#5b7fb0")
    val DEMANDE = Color.parseColor("#a96fa3")
    val ESSAI = Color.parseColor("#b0894e")
    val AUTRE = Color.parseColor("#8f9a5e")
    val PERSO = Color.parseColor("#6c8b65")
    val OVER = Color.parseColor("#8c8c8c")
    val TODAY = Color.parseColor("#b25b5b")

    /** Mélange srgb façon `color-mix(in srgb, c1 ratio%, c2)`. */
    fun mix(c1: Int, ratio: Double, c2: Int): Int {
        val r = (Color.red(c1) * ratio + Color.red(c2) * (1 - ratio)).roundToInt()
        val g = (Color.green(c1) * ratio + Color.green(c2) * (1 - ratio)).roundToInt()
        val b = (Color.blue(c1) * ratio + Color.blue(c2) * (1 - ratio)).roundToInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}

object Statuts {

    /** Teinte d'un statut (gris « terminé » si l'événement est clôturé). */
    fun hue(statut: Statut, etape: Int): Int =
        if (etape == ETAPE_TERMINE) Palette.OVER else when (statut) {
            Statut.RESERVE -> Palette.RESERVE
            Statut.DEMANDE -> Palette.DEMANDE
            Statut.ESSAI -> Palette.ESSAI
            Statut.AUTRE -> Palette.AUTRE
            Statut.PERSO, Statut.PERSO_FULL -> Palette.PERSO
            Statut.ANNULE -> Palette.OVER
        }

    /** Libellé court (chip), comme `statutLabel`. */
    fun label(statut: Statut, etape: Int): String =
        if (etape == ETAPE_TERMINE) "Terminé" else when (statut) {
            Statut.RESERVE -> "Réservé"
            Statut.DEMANDE -> "Demande"
            Statut.ESSAI -> "Essai"
            Statut.AUTRE -> "Autre"
            Statut.PERSO, Statut.PERSO_FULL -> "Perso"
            Statut.ANNULE -> "Annulé"
        }

    /** Libellé long (carte événement), comme le `typeLabel` du widget d'origine. */
    fun typeLabel(statut: Statut, etape: Int): String = when {
        etape == ETAPE_TERMINE -> "Terminé"
        statut == Statut.RESERVE -> "Mariage réservé"
        statut == Statut.DEMANDE -> "Demande de mariage"
        statut == Statut.ESSAI -> "Essai"
        statut == Statut.AUTRE -> "Autre (shooting, tournage…)"
        statut == Statut.PERSO || statut == Statut.PERSO_FULL -> "Perso"
        statut == Statut.ANNULE -> "Annulé"
        else -> "Événement"
    }

    // --- Couleurs dérivées pour les cases du calendrier (look « teinté ») ------

    /** Fond teinté d'une case occupée : `color-mix(statut 12%, surface)`. */
    fun cellFill(hue: Int): Int = Palette.mix(hue, 0.12, Palette.SURFACE)

    /** Bord d'une case occupée : `color-mix(statut 32%, border)`. */
    fun cellStroke(hue: Int): Int = Palette.mix(hue, 0.32, Palette.BORDER)

    /** Texte sur fond teinté : `color-mix(statut 78%, black)`. */
    fun darkOnTint(hue: Int): Int = Palette.mix(hue, 0.78, Color.BLACK)
}
