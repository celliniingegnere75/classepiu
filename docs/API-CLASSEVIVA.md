# API ClasseViva

Base: `https://web.spaggiari.eu/rest/v1`. Gli endpoint sono ricavati dall'app ufficiale
ClasseViva Docenti. `{id}` è l'identificativo del docente, `{tipo}` il tipo di classe
(di solito `ordinary`).

> Il servizio non è raggiungibile da tutti gli indirizzi: per verificare una risposta
> conviene copiarla dall'app sul telefono.

## Accesso

| Metodo | Percorso | Uso |
|---|---|---|
| POST | `auth/login` | Login, restituisce il token |

## Bacheca

| Metodo | Percorso | Uso |
|---|---|---|
| GET | `{percorso}/{id}/noticeboard` | Elenco circolari |
| GET | `{percorso}/{id}/noticeboard/attach/{evtCode}/{pubId}/{n}` | Download allegato |
| POST | `{percorso}/{id}/noticeboard/read/{evtCode}/{pubId}/101` | Lettura / adesione |

## Classi e studenti

| Metodo | Percorso | Uso |
|---|---|---|
| GET | `teachers/{id}/classes/mine` | Classi del docente |
| GET | `teachers/{id}/students/list/{tipo}/{classe}` | Elenco studenti |
| GET | `teachers/{id}/students/status2/{tipo}/{classe}/{giorno}` | Stato presenze |
| GET | `teachers/{id}/subjects/list/{tipo}/{classe}` | Materie |

## Voti

| Metodo | Percorso | Uso |
|---|---|---|
| GET | `teachers/{id}/evaluations/grades2/{tipo}/{classe}/{materia}` | Voti |
| GET | `teachers/{id}/evaluations/components/{tipo}/{classe}/{materia}` | Componenti e livelli di voto |
| PUT | `teachers/{id}/evaluations/grades2` | Salvataggio voto |
| DELETE | `teachers/{id}/evaluations/grades2` | Cancellazione voto |

Formato: `{"grades":[{evtId, evtDate, teacherId, studentId, decimalValue, displayValue,
notesForFamily, color, componentDesc, weightFactor, ...}]}`.

Componenti: Scritto/Grafico, Orale, Pratico. Livelli: 48 codici (da 1 a 10 con +, -, ½ e
decimali; giudizi i=4, s=6, dc=7, b=8, ds=9, o=10, m=8, ms=10).
Periodi: 1° quadrimestre dal 15 settembre; il periodo finale ha `isFinal=true`.

## Note

| Metodo | Percorso | Uso |
|---|---|---|
| GET | `teachers/{id}/notes/range/{tipo}/{classe}/{da}/{a}` | Note nel periodo |

Codici: `NTTE` docente, `NTCL` registro, `NTWN` richiami, `NTNT` annotazioni.

## Lezioni

| Metodo | Percorso | Uso |
|---|---|---|
| GET | `teachers/{id}/lessons2/students/{tipo}/{classe}/{giorno}` | Lezioni della classe |
| GET | `teachers/{id}/lessons/mine/{da}/{a}` | Lezioni del docente |
| PUT | `teachers/{id}/lessons/sigs` | Firma delle ore (`evtNumH`) |

## Portale web (fuori dalla REST)

| URL | Uso |
|---|---|
| `auth-p7/app/default/AuthApi4.php?a=aLoginPwd` | Login al portale |
| `auth/app/default/OtpApi.php?a=recStatus` | Email del docente (`data.emailAddress`) |
| `cvv/app/default/regclasse.php` (GET) | Registro di classe: voci «tipo attività» e progetti di scuola e territorio |
| `cvv/app/default/regclasse_io.php` (POST `action=FIRMA_INSERT`) | Nuova firma: `materia`, `tipo_attivita`, `ora_posizione`, `numero_ore`, `peso_ora`, `uda_firma`, `fase_firma`, `id_progetto_set`, `text_nota`, `supplenza`/`conteggioassenze` |
| `cmp/app/default/rubrica_io.php?ope=getUdaFirma` (GET) | UDA proposte per materia e classe |
| `cvv/app/default/regclasse_update_firma.php` (GET, poi POST `ope=UPD`) | Modifica firma: `ids`, `data`, `evento_id`, `text_nota`, `text_nota_2`, `attivita`, `id_progetto_set`, `id_uda` |
| `cvv/app/default/regclasse_cancella_firma.php` (POST `ope=DEL`) | Cancella firma: `posizioni` (sigStamp), `evento_codice=FR`, `ora_posizione`, `evento_data_ora`, `materia_id`, `autore_id`, `classe_ident`, `evento=--`, `isgruppo` |
