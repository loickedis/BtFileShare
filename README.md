# BtFileShare

Échange de fichiers entre téléphones **par Bluetooth, 100 % gratuit** : pas de compte, pas de serveur, pas de pub, pas de quota.

Version : **Android (Kotlin)** — fonctionne d'Android 8.0 (API 26) à Android 14+.

## Principe technique

| Élément | Choix | Pourquoi |
|---|---|---|
| Transport | Bluetooth Classic, socket **RFCOMM** (UUID partagé `8ce255c0-200a-11e0-ac64-0800200c9a66`) | Natif Android, aucune dépendance, débit ~100–200 Ko/s+ |
| Rôle | L'app est **serveur ET client** : elle écoute en continu et peut se connecter à l'autre téléphone | Les 2 téléphones ouvrent l'app, c'est symétrique |
| Protocole | 1 ligne d'en-tête `FILE\|<nom>\|<taille>\n` puis les octets bruts | Simple, fiable, flux avec progression |
| Réception | Enregistrement dans **Téléchargements** via MediaStore | Aucune permission de stockage sur Android 10+ |
| Détection iPhone | Scan BLE + identifiant constructeur **Apple (0x004C)** + noms `iPhone/iPad/MacBook/…` | Les iPhone s'annoncent en BLE avec ce company ID |

⚠️ **Limite iOS** : Apple interdit le transfert de fichiers en Bluetooth classique (pas d'OBEX/OPP sur iOS). L'app **détecte** les iPhones (badge 🍎) et l'explique, mais le transfert vers/depuis un iPhone nécessitera une alternative Wi‑Fi (type AirDrop-like) — piste pour la v2.

## Structure

```
BtFileShare/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/btfileshare/
│       │   ├── MainActivity.kt        UI, permissions, orchestration
│       │   ├── DeviceScanner.kt       découverte classic + BLE (détection Apple)
│       │   ├── TransferManager.kt     serveur/client RFCOMM, protocole, MediaStore
│       │   ├── DeviceAdapter.kt       liste des appareils
│       │   ├── FileSaver.kt           enregistrement dans Téléchargements (MediaStore)
│       │   ├── WifiShareServer.kt     serveur HTTP local + page web (mode iPhone)
│       │   └── DeviceInfo.kt          modèle + heuristique Apple
│       └── res/
├── build.gradle
├── settings.gradle
└── gradle/wrapper/gradle-wrapper.properties
```

## Compiler l'APK

### Avec Android Studio (le plus simple)
1. Ouvre le dossier `BtFileShare` : **File > Open**.
2. Laisse le sync Gradle se terminer (il télécharge Gradle 8.7 automatiquement).
3. **Build > Build Bundle(s)/APK(s) > Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`.
4. Copie l'APK sur ton téléphone et installe-le (autorise « sources inconnues »).

Ou directement : branche ton téléphone en USB (débogage USB activé) → bouton **Run ▶**.

### En ligne de commande (VS Code)
Prérequis : JDK 17, Android SDK (`sdkmanager`), et une install Gradle ≥ 8.7 :
```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=<chemin jdk-17>
gradle assembleDebug
```
L'APK sort dans `app/build/outputs/apk/debug/app-debug.apk`.

## Mode Wi-Fi / iPhone (v2, sans internet)

Transfère aussi avec un **iPhone**, sans app à installer et **sans internet** :
l'Android crée son point d'accès mobile (ou les deux sont sur le même Wi-Fi),
l'iPhone ouvre le lien via le QR code — la communication reste 100 % locale.

1. Côté Android : **Ajouter au partage** (fichiers à proposer) → **Wi-Fi / iPhone**
2. Active le **point d'accès mobile** Android (Réglages → Point d'accès) ou le même Wi-Fi que l'iPhone
3. L'app affiche un **QR code** : sur l'iPhone, ouvre l'appareil photo, vise le QR code, ouvre le lien dans **Safari**
4. La page web permet de **télécharger** les fichiers partagés et d'**envoyer** des fichiers vers l'Android

⚠️ Si iOS affiche « Aucune connexion internet » sur le hotspot, c'est normal — le transfert est local, ignore l'avertissement.

Dépendances ajoutées : NanoHTTPD (serveur HTTP, ~50 Ko) et ZXing core (QR code, sans la partie scan).

## Utilisation Bluetooth (sur les 2 téléphones Android)

1. Installe l'app sur les deux téléphones, ouvre-la, accepte les permissions, active le Bluetooth.
2. Sur le récepteur : **Rendre visible** (et garder l'app ouverte).
3. Sur l'émetteur : **Scanner** → les appareils apparaissent (appairés en premier).
4. **Choisir un fichier à envoyer** → tape l'appareil destinataire.
5. Le récepteur voit la progression ; le fichier arrive dans **Téléchargements**.

## Permissions utilisées

- Android 12+ : `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- Android 6–11 : `ACCESS_FINE_LOCATION` (obligatoire pour la découverte BT)
- Android 9 et moins : `WRITE_EXTERNAL_STORAGE` (enregistrement dans Téléchargements)

## V2 envisagée

- Transfert vers iPhone via Wi‑Fi (mDNS/HTTP local, style AirDrop alternatif)
- Reprise de transfert interrompu, checksum de fin
- Notifications de réception, envoi multi-fichiers
