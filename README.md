# classePiù

App Android per docenti che usano il registro elettronico **ClasseViva** (Spaggiari).
Raccoglie in un'unica app le operazioni quotidiane del docente: orario, firma delle lezioni,
voti, note, bacheca delle circolari e inoltro delle circolari per email.

Sviluppata da Cristian Cellini (ITT "G. e M. Montani", Fermo).

## Funzioni principali

| Area | Cosa fa |
|---|---|
| **Home** | Pagina iniziale (in preparazione) |
| **Registro** | Classi e materie del docente, elenco studenti, voti (lettura, inserimento, cancellazione), note, lezioni e firma delle ore |
| **Bacheca** | Circolari ClasseViva con allegati, adesione/lettura e inoltro per email |
| **Impostazioni** | Credenziali (salvate cifrate), orario settimanale, collegamento Gmail, controllo automatico delle nuove circolari |

## Struttura del repository

```
.
├── app/                      progetto Gradle (aprire questa cartella in Android Studio)
│   ├── gradlew, gradle/      Gradle wrapper (versione fissata)
│   ├── build.gradle.kts      plugin Android e Kotlin
│   ├── settings.gradle.kts
│   └── classepiu/            modulo dell'app
│       ├── build.gradle.kts  versione, firma, dipendenze
│       ├── classepiu.jks     keystore di firma
│       └── src/
│           ├── main/java/it/cellini/classepiu/   codice Kotlin
│           ├── main/res/                         risorse grafiche
│           └── test/                             test unitari
├── docs/                     documentazione tecnica
└── .github/workflows/        compilazione automatica con GitHub Actions
```

## Documentazione

- [Architettura dell'app](docs/ARCHITETTURA.md): schermate, file sorgenti e come sono collegati
- [API ClasseViva](docs/API-CLASSEVIVA.md): endpoint REST usati e formato dei dati
- [Rami, compilazione e rilasci](docs/RILASCI.md): come si lavora sul repository e come nasce l'APK
- [Linee guida grafiche](docs/GRAFICA.md): colori, scritte e componenti

## Compilare in locale

Requisiti: JDK 17 e Android SDK (API 34).

```bash
cd app
./gradlew testDebugUnitTest      # test unitari
./gradlew :classepiu:assembleRelease
```

L'APK firmato si trova in `app/classepiu/build/outputs/apk/release/`.

## Dati tecnici

- Package: `it.cellini.classepiu`
- minSdk 26 (Android 8.0), target/compile SDK 34
- Kotlin 1.9, Android Gradle Plugin 8.5, Java 17
- Interfaccia scritta interamente in codice (nessun layout XML), una sola Activity principale con Fragment
