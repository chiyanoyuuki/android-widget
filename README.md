# Planning Cloé — widget Android

Widget d'écran d'accueil (Pixel / Android) qui affiche le **calendrier mensuel**
du planning IntraCCB, avec **les mêmes couleurs que l'intra** pour les jours
indisponibles. Permet de voir d'un coup d'œil quels jours sont libres sans ouvrir
le site.

- ◀ ▶ : changer de mois
- Tap sur le **titre du mois** : revenir au mois courant **et** rafraîchir les données
- Tap sur un **jour** : ouvre l'intra (`https://www.cloechaudronbeauty.com/intraccb/`)

Les données viennent du même endpoint que l'app :
`http://cloechaudronbeauty.com/backend/api/cloeplanning.php?artiste=cloe`
(public, pas d'authentification, **HTTP en clair** — voir
`network_security_config.xml`). Elles sont mises en cache 30 min et
rafraîchies automatiquement, ou manuellement via le tap sur le titre.

## Deuxième widget — « Prochain événement »

Un widget séparé (à ajouter comme le premier) qui affiche **un événement à
venir à la fois**, avec toutes ses infos utiles :

- ◀ ▶ : parcourir les événements **triés par date** (les événements passés
  sont exclus). Le compteur central indique la position (`3 / 12`).
- Tap sur le **compteur** : rafraîchir les données et revenir au prochain
  événement.
- Tap sur la **carte** : ouvre l'intra.

> Le détail affiché est pour l'instant **générique** (date, type, puis tous les
> champs non vides de l'événement). Les libellés seront affinés une fois le
> format exact d'un événement connu — voir `buildDetails()` dans
> `PlanningRepository.kt`.

## Légende des couleurs (identiques au SCSS de l'intra)

| Couleur | Signification |
|---|---|
| 🔵 Bleu `#2972C5` | Mariage réservé (`reserve`) |
| 🟣 Magenta `#F132E1` | Demande de mariage (`demande`) |
| 🟠 Ocre `#B67600` | Essai (`essai`) |
| 🫒 Kaki `#AFA776` | Autre (shooting, tournage, animation…) (`autre`) |
| 🔴 Rouge | Perso (`perso` / `persofull`) |
| ⚫ Noir | Prestation terminée (`etape == 999`) |
| ⬜ Blanc | Jour libre |

- **Anneau violet** : aujourd'hui
- **Bord rouge** : mariage réservé sans planning renseigné (`noplanning`)
- **Bord ocre** : mariage réservé sans essai renseigné (`noessai`)
- **Pastille** en haut à droite : nombre d'événements ce jour-là (si > 1)

## Construire l'APK

> ⚠️ Ce projet n'a pas pu être compilé dans l'environnement de génération
> (pas de SDK Android disponible). Il se construit normalement avec Android
> Studio, qui fournit le SDK.

### Option A — Android Studio (le plus simple)

1. Ouvrir Android Studio → **Open** → choisir le dossier `android-widget/`.
2. Laisser la synchronisation Gradle se faire (télécharge AGP 8.5.2, Gradle 8.7,
   le SDK 34 si besoin).
3. **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**.
4. L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Ligne de commande

Prérequis : JDK 17+, et le SDK Android (variable `ANDROID_HOME` ou fichier
`local.properties` contenant `sdk.dir=/chemin/vers/Android/sdk`).

```bash
cd android-widget
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

## Installer sur le Pixel

### Via ADB (USB, débogage activé)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Sans PC

Copier le fichier `app-debug.apk` sur le téléphone et l'ouvrir. Autoriser
« Installer des applications inconnues » pour l'explorateur de fichiers.

## Ajouter le widget

1. Ouvrir l'app **Planning Cloé** une fois (charge le planning et « réveille »
   l'app).
2. Appui long sur l'écran d'accueil ▸ **Widgets** ▸ **Planning Cloé**.
3. Glisser le widget. Il est redimensionnable (4×4 conseillé).

## Personnalisation

| Quoi | Où |
|---|---|
| Artiste / endpoint API | `ENDPOINT` dans `PlanningRepository.kt` |
| URL ouverte au clic | `SITE_URL` dans `PlanningWidgetProvider.kt` |
| Couleurs des statuts | `Palette` dans `PlanningRepository.kt` |
| Durée du cache | `TTL_MS` dans `PlanningRepository.kt` (défaut 30 min) |

## Limitations

- Le **swipe** pour changer de mois n'est pas possible dans un widget Android :
  on utilise les flèches ◀ ▶ (limitation de `RemoteViews`).
- Le rafraîchissement automatique périodique est limité à ~30 min par le système
  (`updatePeriodMillis`). Le tap sur le titre force une mise à jour immédiate.
- Si le réseau échoue **et** qu'aucune donnée n'a jamais été chargée, le widget
  affiche un message ; sinon il garde le dernier planning connu (hors-ligne OK).
- L'endpoint étant public, n'importe qui connaissant l'URL peut lire le planning.
  Si tu veux le restreindre un jour (token, auth), il faudra ajouter l'en-tête
  correspondant dans `fetch()`.

## Structure

```
android-widget/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/cloechaudron/planning/
│   │   ├── PlanningRepository.kt     # fetch + cache + parsing + construction du mois
│   │   ├── PlanningWidgetProvider.kt # cycle de vie du widget, navigation, clics
│   │   ├── PlanningWidgetService.kt  # fabrique les 42 cases (GridView)
│   │   └── MainActivity.kt           # petit écran d'aide
│   └── res/                          # layouts, drawables, couleurs, icône…
├── build.gradle.kts / settings.gradle.kts / app/build.gradle.kts
└── gradle/ (wrapper 8.7)
```
