# Planning Cloé — widgets Android (lecture seule)

Widgets d'écran d'accueil (Pixel / Android) qui reflètent **IntraCCB2** (l'outil
de gestion de *Cloé Chaudron Beauty*) en **lecture seule** : on consulte le
planning, les fiches et le bilan, mais **rien n'est modifiable** et **aucun
document PDF** (devis / facture / planning) n'est généré.

> Réécriture complète des widgets, alignée sur le **nouveau** IntraCCB2 (Angular 21).
> La logique chiffrée (prix, factures, statistiques), les statuts et les couleurs
> ont été **reportés fidèlement** depuis l'app actuelle — les anciens widgets se
> basaient sur l'ancien code et ses anciennes teintes.

## Les widgets

| Widget | Rôle |
|---|---|
| **Calendrier** | Vue mensuelle. Jours « teintés » selon le statut, pastilles « manque essai / planning », anneau du jour, badge multi-événements. Flèches du bas = mois, du haut = année ; tap sur le mois/année = revenir au mois courant ; tap sur un jour = **fiche détail** (lecture seule). |
| **Prochain événement** | Un événement à venir à la fois (trié par date puis heure). Type, mariée, lieu + cérémonie, **horaires du jour-J** (sur place), **reste à payer**. ◀ ▶ pour parcourir, tap sur le compteur = actualiser, 📞 / ✉ pour appeler / écrire, tap sur la carte = fiche détail. |
| **Bilan financier** | Indicateurs du tableau de bord : total, encaissé, reste à encaisser, estimation, **net** (après abattement + pourboires), heures travaillées et €/h, compteurs (mariages / demandes / essais / autres / terminés), et un petit **graphe du revenu mensuel** de l'année. Flèches du haut = année, du bas = mois (ou « toute l'année »). |
| **Actualiser** (1×1) | Force des données fraîches et rafraîchit les trois autres widgets en un tap. |

La **fiche détail** (ouverte en tapant un jour ou une carte) montre, en lecture
seule : coordonnées (avec appel / mail), essai, lieu du mariage, horaires du
jour-J et **récapitulatif chiffré** (qté / prestation / réduc / total, puis
total / payé / reste à payer, part prestataire et pourboire). On navigue entre
les événements d'une même journée avec ◀ ▶.

## Données

Même source que l'app, désormais en **HTTPS** :

```
https://www.cloechaudronbeauty.com/backend/api/cloeplanning.php?artiste=cloe
```

Public, sans authentification. Les données sont **mises en cache** ; le réseau
n'est sollicité que si rien n'est en cache ou si on demande une actualisation
(widget « Actualiser », tap sur le compteur d'un widget, ou bouton dans l'app).
**Naviguer** (mois, année, événement, période du bilan) ne refetch jamais.

## Légende des couleurs (charte du nouveau IntraCCB2)

| Couleur | Signification |
|---|---|
| 🔵 `#5B7FB0` | Mariage réservé (`reserve`) |
| 🟣 `#A96FA3` | Demande de mariage (`demande`) |
| 🟤 `#B0894E` | Essai (`essai`) |
| 🫒 `#8F9A5E` | Autre (shooting, tournage…) (`autre`) |
| 🟢 `#6C8B65` | Perso (`perso` / `persofull`) |
| ⚪ `#8C8C8C` | Terminé (`etape == 999`) |
| ⬜ Blanc | Jour libre |

- **Anneau mauve** `#AA82BA` : aujourd'hui
- **Pastille ocre** : réservé sans essai renseigné
- **Pastille rouge** : réservé sans planning renseigné
- **Badge mauve** (haut-droite) : nombre d'événements ce jour (si > 1)
- Les jours passés sont atténués

## Construire l'APK

> ⚠️ Le SDK Android n'est pas disponible dans l'environnement de génération :
> le projet se construit avec **Android Studio** (qui fournit le SDK) ou en
> ligne de commande si le SDK est installé.

### Android Studio

1. **Open** → choisir le dossier `android-widget/`.
2. Laisser la synchro Gradle se faire (AGP 8.5.2, Gradle 8.7, SDK 34).
3. **Build ▸ Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`.

### Ligne de commande

Prérequis : JDK 17+, SDK Android (`ANDROID_HOME` ou `local.properties` avec
`sdk.dir=…`).

```bash
cd android-widget
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

## Installer & ajouter les widgets

1. Installer l'APK (`adb install -r …` ou copier le fichier sur le téléphone).
2. Ouvrir l'app **Planning Cloé** une fois (charge le planning, « réveille » l'app).
3. Appui long sur l'écran d'accueil ▸ **Widgets** ▸ **Planning Cloé** ▸ glisser
   le ou les widgets souhaités (redimensionnables).

## Personnalisation

| Quoi | Où |
|---|---|
| Artiste / endpoint API | `ENDPOINT` dans `PlanningRepository.kt` |
| Couleurs des statuts / charte | `Palette` dans `Statuts.kt` et `res/values/colors.xml` |
| Taux d'abattement du « net » | `TAX_RATE` dans `Stats.kt` (0,24 par défaut) |

## Structure

```
android-widget/app/src/main/
├── AndroidManifest.xml
├── java/com/cloechaudron/planning/
│   ├── Domain.kt                 # modèles + parseur JSON (port lecture du mapper)
│   ├── Pricing.kt                # moteur de prix (port de PricingService)
│   ├── Stats.kt                  # indicateurs du bilan (port de StatsService)
│   ├── Statuts.kt                # charte + libellés de statut (palette harmonisée)
│   ├── Formats.kt                # dates FR + montants
│   ├── EventCard.kt              # carte événement (lieu, horaires, reste à payer)
│   ├── PlanningRepository.kt     # fetch HTTPS + cache + sélecteurs + mois
│   ├── PlanningWidgetProvider.kt # widget calendrier (+ PlanningWidgetService)
│   ├── PlanningWidgetService.kt  # fabrique les 42 cases (GridView)
│   ├── EventWidgetProvider.kt    # widget « prochain événement »
│   ├── BilanWidgetProvider.kt    # widget « bilan financier » (+ graphe)
│   ├── RefreshWidgetProvider.kt  # widget 1×1 « Actualiser »
│   ├── DayDetailActivity.kt      # fiche détail (lecture seule)
│   └── MainActivity.kt           # écran d'aide / diagnostic
└── res/                          # layouts, drawables, couleurs, widget-infos…
```

## Limitations

- Les widgets ne se rafraîchissent **pas** seuls (`updatePeriodMillis = 0`) ; la
  récupération est manuelle (widget « Actualiser », compteurs, bouton dans l'app).
- Le **graphe** du bilan est volontairement compact (barres du revenu mensuel de
  l'année) ; pour l'analyse fine, l'app reste la référence.
- L'endpoint étant public, n'importe qui connaissant l'URL peut lire le planning.
- Tout est en **lecture seule** : pour créer / modifier un événement, un devis,
  une facture ou un planning, utiliser IntraCCB2.
