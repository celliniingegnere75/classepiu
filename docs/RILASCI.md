# Rami, compilazione e rilasci

## Rami

| Ramo | Ruolo |
|---|---|
| `main` | Versione ufficiale. Ogni salvataggio avvia la compilazione |
| `apk` | Scritto solo da GitHub Actions: contiene `ClassePiu.apk`, `build.log`, `stato.txt` |
| altri rami | Lavori in corso (es. `lavoro`, `barra-ingranaggio`). Si uniscono a `main` quando sono pronti |

Regola: le modifiche si preparano su un ramo di lavoro e si uniscono a `main` solo quando
si vuole un nuovo APK.

## Compilazione automatica

| Workflow | Quando parte | Cosa produce |
|---|---|---|
| `build-android.yml` | push su `main`, pull request, avvio manuale | Test unitari + APK di debug come artefatto (14 giorni) |
| `classepiu.yml` | push su `main` (o `classepiu`) che tocca `app/`, avvio manuale | APK release firmato, pubblicato sul ramo `apk` |

## Versione

Calcolata in `app/classepiu/build.gradle.kts` dal numero di esecuzione di GitHub Actions:
`versionCode = numero esecuzione`, `versionName = 1.0.(49 + numero esecuzione)`.
Così ogni APK si installa sopra il precedente.

## Firma

L'APK è firmato con `app/classepiu/classepiu.jks`. Il keystore è nel repository pubblico per scelta
(app distribuita solo a uso interno). Per una pubblicazione sul Play Store andrà spostato nei
segreti di GitHub e sostituito.
