# Architettura

L'app ha una sola Activity principale (`MainActivity`) che ospita le pagine come Fragment.
L'interfaccia è costruita in Kotlin, senza layout XML, con le funzioni di supporto di `Ui.kt`.

## Schermate

| Schermata | File | Note |
|---|---|---|
| Avvio | `Splash.kt` | Animazione del logo bianco su fondo azzurro |
| Accesso | `AccessoActivity.kt` | Login ClasseViva |
| Contenitore | `MainActivity.kt` | Fascia azzurra in alto, barra delle pagine in basso, navigazione tra i Fragment |
| Home | `HomeFragment.kt` | Per ora vuota |
| Registro | `RegistroFragment.kt` | Scelta classe/materia e apertura delle schede |
| Bacheca | `BachecaFragment.kt` | Circolari, allegati, inoltro email |
| Impostazioni | `ImpostazioniFragment.kt` | Sezioni "lezioni" e "funzioni" |
| Pagina web | `WebActivity.kt` | Apertura di contenuti ClasseViva nel browser interno |

### Schede del registro

Tutte derivano dalla classe base `Scheda`.

| Scheda | File |
|---|---|
| Elenco generico | `ListaScheda.kt` |
| Dettaglio | `DettaglioScheda.kt` |
| Studenti | `StudentiScheda.kt` |
| Voti | `VotiScheda.kt` |

## Componenti grafici

| File | Contenuto |
|---|---|
| `Ui.kt` | Funzioni per creare testi, bottoni, campi, righe e colonne |
| `Pagine.kt` | Barra delle pagine in sovraimpressione e relativo alone |
| `StrisciaGiorni.kt` | Selettore del giorno |
| `Orologio.kt` | Orologio analogico disegnato a mano |

## Dati e servizi

| File | Contenuto |
|---|---|
| `Api.kt` | Client REST di ClasseViva (`ClasseViva`), modelli dati (`Circolare`, `Studente`, ...) |
| `ClasseVivaPayloadValidation.kt` | Controllo del contenuto delle richieste prima dell'invio (voti) |
| `Store.kt` | Impostazioni cifrate (`Impostazioni`), orario (`Orario`), registro attività (`Diario`) |
| `Scuola.kt` | Dati fissi della scuola |
| `FinestraRientro.kt` | Notifiche dell'orario al rientro in primo piano (fino a 3, ognuna col suo stato e i suoi timer) |
| `PianoNotifiche.kt` | Regole delle notifiche (comparsa, riduzione, scadenza a due ore scolastiche) e vita delle finestre, provate da `PianoNotificheTest` |
| `Controllo.kt` | Controllo periodico delle nuove circolari con WorkManager e notifiche |
| `GoogleMail.kt` | Accesso Google e invio tramite Gmail API (scope `gmail.send`) |
| `Mailer.kt` | Composizione delle email con gli allegati |

## Flusso tipico

1. All'avvio `MainActivity` verifica le credenziali salvate; se mancano apre `AccessoActivity`.
2. `ClasseViva` esegue il login (`auth/login`) e conserva il token per le chiamate successive.
3. Le pagine chiedono i dati a `ClasseViva` in background (coroutine) e li mostrano.
4. `Controllo` gira in background con WorkManager, scarica la bacheca, notifica le circolari nuove
   e, se attivato, le inoltra per email.

## Test

`src/test/.../ClasseVivaPayloadValidationTest.kt` verifica che le richieste di salvataggio dei voti
contengano i campi obbligatori. I test girano a ogni compilazione automatica.
