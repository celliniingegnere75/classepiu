package it.cellini.classepiu

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.Calendar

/** Finestra temporanea classePiù mostrata solo al rientro in primo piano. */
object FinestraRientro {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null

    fun mostra(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ora = Calendar.getInstance()
        val lezione = runCatching { Orario(activity).lezioneAlle(ora) }.getOrNull() ?: return
        val fascia = lezione.fascia
        val minutiAdesso = ora.get(Calendar.HOUR_OF_DAY) * 60 + ora.get(Calendar.MINUTE)
        val secondiAdesso = ora.get(Calendar.SECOND)
        val residui = ((fascia.fine - minutiAdesso) * 60 - secondiAdesso).coerceAtLeast(0)
        if (residui <= 0) return

        dialog?.dismiss()
        val azzurro = 0xFF69BCE8.toInt()
        val scuro = 0xFF333333.toInt()
        val celesteClasse = 0xFF5598BD.toInt()
        val outfitRegular = runCatching { Typeface.createFromAsset(activity.assets, "outfit_regular.ttf") }.getOrDefault(Typeface.DEFAULT)
        val outfitBold = runCatching { Typeface.createFromAsset(activity.assets, "outfit_bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
        // scheda bianca arrotondata; la fascia azzurra in alto col logo bianco è quella della schermata principale
        val radice = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(24).toFloat()
            }
            clipToOutline = true
        }
        val intestazione = FrameLayout(activity).apply {
            setBackgroundColor(azzurro)
            setPadding(0, activity.dp(16), 0, activity.dp(16))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.logo_bianco)
                adjustViewBounds = true
            }, FrameLayout.LayoutParams(activity.dp(120), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // Freccia stilizzata per ridurre la notifica a una striscia.
            val frecciaSu = TextView(activity).apply {
                text = "⌃"
                textSize = 29f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "Riduci notifica"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x35FFFFFF)
                    setStroke(activity.dp(1), 0x70FFFFFF)
                }
                isClickable = true
                isFocusable = true
            }
            addView(frecciaSu, FrameLayout.LayoutParams(activity.dp(38), activity.dp(38), Gravity.TOP or Gravity.START).apply {
                leftMargin = activity.dp(10)
                topMargin = activity.dp(4)
            })
            // X stilizzata in un cerchio semitrasparente, sempre in alto a destra
            addView(TextView(activity).apply {
                text = "×"
                textSize = 30f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "Chiudi finestra"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x35FFFFFF)
                    setStroke(activity.dp(1), 0x70FFFFFF)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    handler.removeCallbacksAndMessages(null)
                    val daChiudere = dialog
                    dialog = null
                    daChiudere?.dismiss()
                }
            }, FrameLayout.LayoutParams(activity.dp(38), activity.dp(38), Gravity.TOP or Gravity.END).apply {
                rightMargin = activity.dp(10)
                topMargin = activity.dp(4)
            })
        }
        radice.addView(intestazione, LinearLayout.LayoutParams(-1, -2))
        // Riga superiore come il disegno di Cristian: "aula" e il suo codice a sinistra, "classe" e il suo codice a destra;
        // etichette in azzurro chiaro e codici in grassetto più scuri, così si distinguono.
        fun blocco(etichetta: String, valore: String, allineamento: Int) = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = allineamento
            addView(TextView(activity).apply {
                text = etichetta
                textSize = 16f
                typeface = outfitRegular
                setTextColor(azzurro)
                includeFontPadding = false
                setSingleLine(true)
            })
            addView(TextView(activity).apply {
                text = valore
                textSize = 22f
                typeface = outfitBold
                setTextColor(celesteClasse)
                includeFontPadding = false
                setSingleLine(true)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(2) })
        }
        radice.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), 0, activity.dp(20), 0)
            if (lezione.aula.isNotBlank()) {
                addView(blocco("aula", lezione.aula, Gravity.START))
                addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
                addView(blocco("classe", lezione.classe, Gravity.END))
            } else {
                gravity = Gravity.CENTER
                addView(blocco("classe", lezione.classe, Gravity.CENTER_HORIZONTAL))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(28) })

        // Materia dell'ora subito sotto aula e classe: una sola riga, puntini se troppo lunga.
        radice.addView(TextView(activity).apply {
            text = lezione.materia
            textSize = 11.25f
            typeface = outfitRegular
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })

        // Ghiera sotto la materia, ben staccata.
        radice.addView(GhieraLezione(activity, fascia, outfitBold), LinearLayout.LayoutParams(activity.dp(87), activity.dp(87)).apply {
            topMargin = activity.dp(36)
        })

        // Stato firma: viene verificato realmente su ClasseViva, senza bloccare l'apertura della finestra.
        val statoFirma = TextView(activity).apply {
            text = "verifico la firma…"
            textSize = 21f // 14 + 50%, come chiesto da Cristian
            typeface = outfitBold
            gravity = Gravity.CENTER
            setTextColor(0xFF777777.toInt())
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(12))
        }
        // materia del registro sotto "ora firmata": 40% più piccola, una sola riga, puntini se troppo lunga
        val materiaFirma = TextView(activity).apply {
            textSize = 12.6f // 8,4 sp + 50%, come chiesto da Cristian
            typeface = outfitRegular
            gravity = Gravity.CENTER
            setTextColor(0xFF16803C.toInt())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            visibility = View.GONE
        }
        radice.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(statoFirma, LinearLayout.LayoutParams(-1, -2))
            addView(materiaFirma, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -activity.dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(21) })
        // compare solo se l'ora non è firmata: firma tipologia "lezione" con argomento vuoto
        // come il tasto "compilazione magica": cappello a sinistra, "firma" sopra e "rapida" sotto; almeno 128×44 dp ma
        // l'altezza cresce col testo (a 44 fissi "rapida" veniva tagliata), cappello a 18 dp dal bordo e 18 dalla scritta, scritta a 20 dal bordo destro (disegno di Cristian)
        val firmaRapida = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(azzurro)
                cornerRadius = activity.dp(22).toFloat()
            }
            setPadding(activity.dp(4), activity.dp(2), activity.dp(4), activity.dp(2))
            minimumWidth = activity.dp(128)
            minimumHeight = activity.dp(44)
            addView(android.widget.ImageView(activity).apply {
                setImageResource(R.drawable.ic_cappello_mago)
                translationX = -activity.dp(10).toFloat()
            }, LinearLayout.LayoutParams(activity.dp(28), activity.dp(26)).apply { rightMargin = activity.dp(6); bottomMargin = activity.dp(1) })
            // "firma" e "rapida" allineate a sinistra, una sopra l'altra e più vicine tra loro
            addView(TextView(activity).apply {
                text = "firma\nrapida"; textSize = 14.3f; typeface = outfitBold; setTextColor(Color.WHITE)
                includeFontPadding = false; gravity = Gravity.START
                setLineSpacing(0f, 0.9f)
                translationX = -activity.dp(10).toFloat()
            })
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        // Pulsante alternativo: per questa lezione indica che non si vuole firmare e nasconde entrambi i pulsanti.
        val nonFirmo = TextView(activity).apply {
            text = "NON\nfirmo"
            textSize = 14.3f
            typeface = outfitBold
            setTextColor(0xFF880E4F.toInt())
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            setLineSpacing(0f, 0.9f)
            setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6))
            // Pulsante secondario rosa, con le due righe centrate.
            background = GradientDrawable().apply {
                setColor(0xFFFFF7FA.toInt())
                cornerRadius = activity.dp(22).toFloat()
                setStroke(activity.dp(1), 0xFFF48FB1.toInt())
            }
            isClickable = true
            isFocusable = true
        }
        val rigaPulsanti = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(firmaRapida, LinearLayout.LayoutParams(activity.dp(150), activity.dp(60)))
            addView(nonFirmo, LinearLayout.LayoutParams(activity.dp(150), activity.dp(60)).apply { leftMargin = activity.dp(22) })
        }
        val chiaveNonFirmo = "non_firmo_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())}_${lezione.ora}_${lezione.classe.trim().replace(Regex("\\s+"), "_")}"
        val preferenzeNonFirmo = activity.getSharedPreferences("classepiu_firma_rapida", Activity.MODE_PRIVATE)
        // Durante il controllo della firma entrambi i pulsanti restano nascosti.
        // Saranno mostrati solo se ClasseViva conferma che l'ora non è firmata.
        rigaPulsanti.visibility = View.GONE
        firmaRapida.visibility = View.GONE
        firmaRapida.isEnabled = false
        firmaRapida.alpha = .5f
        nonFirmo.setOnClickListener {
            preferenzeNonFirmo.edit().putBoolean(chiaveNonFirmo, true).apply()
            rigaPulsanti.visibility = View.GONE
        }
        // I due pulsanti sono affiancati e compaiono solo se l'ora non è ancora firmata.
        radice.addView(rigaPulsanti, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -activity.dp(25); bottomMargin = activity.dp(12) })
        // dopo una firma rapida: copia negli appunti cosa si è mandato e cosa ha risposto classeviva
        val copiaLog = TextView(activity).apply {
            text = "copia log"
            textSize = 13f
            typeface = outfitRegular
            setTextColor(azzurro)
            setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
            isClickable = true
            visibility = View.GONE
        }
        radice.addView(copiaLog, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = activity.dp(16) })
        verificaFirma(activity, lezione, statoFirma, materiaFirma, firmaRapida, rigaPulsanti, chiaveNonFirmo, preferenzeNonFirmo, copiaLog)

        // Riepilogo compatto: ora scolastica, codice classe e stato effettivo della firma.
        val oraBreve = when (lezione.ora) {
            1 -> "I° ora"; 2 -> "II° ora"; 3 -> "III° ora"; 4 -> "IV° ora"; 5 -> "V° ora"
            6 -> "VI° ora"; 7 -> "VII° ora"; 8 -> "VIII° ora"; else -> "${lezione.ora}° ora"
        }
        val statoCompatto = TextView(activity).apply {
            text = "verifica…"
            textSize = 12.5f
            typeface = outfitBold
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setTextColor(0xFF777777.toInt())
        }
        val frecciaGiu = TextView(activity).apply {
            text = "⌄"
            textSize = 27f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(0xFF333333.toInt())
            contentDescription = "Espandi notifica"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEAF5FB.toInt())
                setStroke(activity.dp(1), 0xFFB7DCEE.toInt())
            }
            isClickable = true
            isFocusable = true
        }
        val sintesi = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(9), activity.dp(8), activity.dp(14), activity.dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(18).toFloat()
                setStroke(activity.dp(1), 0xFFB7DCEE.toInt())
            }
            addView(frecciaGiu, LinearLayout.LayoutParams(activity.dp(34), activity.dp(34)))
            addView(TextView(activity).apply {
                text = oraBreve
                textSize = 12.5f
                typeface = outfitBold
                setTextColor(0xFF333333.toInt())
                setSingleLine(true)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = activity.dp(7) })
            addView(TextView(activity).apply {
                text = lezione.classe
                textSize = 13f
                typeface = outfitBold
                setTextColor(celesteClasse)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(12); rightMargin = activity.dp(8) })
            addView(statoCompatto, LinearLayout.LayoutParams(-2, -2))
            visibility = View.GONE
        }
        statoFirma.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val testo = s?.toString().orEmpty().lowercase(java.util.Locale.ROOT)
                when {
                    testo.contains("non firmata") || testo.contains("non riuscita") -> {
                        statoCompatto.text = "senza firma"
                        statoCompatto.setTextColor(0xFFD32F2F.toInt())
                    }
                    testo.contains("firmata") -> {
                        statoCompatto.text = "firmato"
                        statoCompatto.setTextColor(0xFF16803C.toInt())
                    }
                    else -> {
                        statoCompatto.text = "verifica…"
                        statoCompatto.setTextColor(0xFF777777.toInt())
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val contenitore = FrameLayout(activity).apply {
            addView(radice, FrameLayout.LayoutParams(-1, -2))
            addView(sintesi, FrameLayout.LayoutParams(-1, -2))
        }

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(contenitore)
        d.setCancelable(false)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.28f)
            setLayout((activity.resources.displayMetrics.widthPixels * .88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog = d
        d.show()
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * .88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        contenitore.alpha = 0f
        contenitore.scaleX = .92f
        contenitore.scaleY = .92f
        contenitore.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
        ControlloNotificaRiducibile(d, radice, sintesi, intestazione.getChildAt(1), frecciaGiu)
        handler.postDelayed({
            if (dialog === d) {
                contenitore.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(220).withEndAction {
                    runCatching { d.dismiss() }
                    if (dialog === d) dialog = null
                }.start()
            }
        }, 9580) // circa 9,8 secondi in tutto, compresa la dissolvenza
    }

    /**
     * Controlla la firma del docente nell'endpoint lessons/mine per la data odierna.
     * L'ora è considerata firmata solo se coincidono docente, classe, materia e fascia oraria.
     */
    private fun verificaFirma(
        activity: Activity,
        lezione: Lezione,
        etichetta: TextView,
        materia: TextView,
        pulsante: View,
        rigaPulsanti: View,
        chiaveNonFirmo: String,
        preferenzeNonFirmo: android.content.SharedPreferences,
        copiaLog: TextView
    ) {
        val data = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        Thread {
            val esito = runCatching {
                val imp = Impostazioni(activity)
                check(imp.pronta) { "Credenziali non configurate" }
                val client = imp.client().also { it.login() }
                val (codiceClassi, classiGrezze) = client.classiGrezze(false)
                check(codiceClassi == 200) { "Classi non disponibili (HTTP $codiceClassi)" }
                val nomi = OrarioDalleFirme.nomiClassi(leggiClassi(classiGrezze))
                val (codice, grezzo) = client.lezioniGrezze(data, data)
                check(codice == 200) { "Firme non disponibili (HTTP $codice)" }
                firmaCorrispondente(grezzo, client.idDocente(), data, lezione, nomi)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !etichetta.isAttachedToWindow) return@runOnUiThread
                esito.fold(
                    onSuccess = { risultato ->
                        if (risultato == null) {
                            etichetta.text = "ora non firmata"
                            etichetta.setTextColor(0xFFD32F2F.toInt())
                            if (!preferenzeNonFirmo.getBoolean(chiaveNonFirmo, false)) {
                                pulsante.visibility = View.VISIBLE
                                pulsante.isEnabled = true
                                pulsante.alpha = 1f
                                rigaPulsanti.visibility = View.VISIBLE
                            }
                            pulsante.setOnClickListener { firmaRapida(activity, lezione, etichetta, materia, pulsante, copiaLog) }
                        } else {
                            mostraFirmata(etichetta, materia, risultato)
                            rigaPulsanti.visibility = View.GONE
                        }
                    },
                    onFailure = {
                        etichetta.text = "firma non verificata"
                        etichetta.setTextColor(0xFF8A6D00.toInt())
                    }
                )
            }
        }.start()
    }

    private fun mostraFirmata(etichetta: TextView, materia: TextView, risultato: EsitoFirma) {
        etichetta.text = "ora firmata"
        etichetta.setTextColor(0xFF16803C.toInt())
        materia.text = risultato.materia
        materia.visibility = if (risultato.materia.isNotBlank()) View.VISIBLE else View.GONE
    }

    /**
     * Firma rapida dell'ora in corso, costruita da zero come una firma nuova: classe da "le mie classi", materia da
     * subjects/list, data e ora dall'orario, tipologia "Lezione" e argomento vuoto (PUT lessons/sigs).
     * Prima e dopo rilegge le firme di oggi: non firma due volte e conferma che la firma è nel registro.
     * La finestra resta aperta finché non si chiude con la X, così l'esito si legge.
     */
    private fun firmaRapida(activity: Activity, lezione: Lezione, etichetta: TextView, materia: TextView, pulsante: View, copiaLog: TextView) {
        handler.removeCallbacksAndMessages(null)
        pulsante.isEnabled = false
        pulsante.alpha = .5f
        etichetta.text = "firmo…"
        etichetta.setTextColor(0xFF777777.toInt())
        val data = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        val log = StringBuilder("firma rapida classePiù ${BuildConfig.VERSION_NAME}\n")
            .append("data $data, ora ${lezione.ora}, classe ${lezione.classe}, materia ${lezione.materia}\n\n")
        Thread {
            val esito = runCatching {
                val client = Impostazioni(activity).client().also { it.login() }
                val mioId = client.idDocente()
                log.append("docente $mioId\n")
                val (codiceClassi, classiGrezze) = client.classiGrezze(false)
                check(codiceClassi == 200) { "classi non disponibili (HTTP $codiceClassi)" }
                val classi = leggiClassi(classiGrezze)
                log.append("le mie classi: ${classi.joinToString { it.chiave }}\n")
                val nomi = OrarioDalleFirme.nomiClassi(classi)
                // se nel frattempo l'ora è stata firmata non si firma due volte
                val (codicePrima, grezzoPrima) = client.lezioniGrezze(data, data)
                check(codicePrima == 200) { "firme di oggi non disponibili (HTTP $codicePrima)" }
                firmaCorrispondente(grezzoPrima, mioId, data, lezione, nomi)?.let { log.append("ora già firmata, non firmo\n"); return@runCatching it }
                val classe = checkNotNull(classi.firstOrNull { it.nome.equals(siglaClasse(lezione.classe), ignoreCase = true) }) {
                    "${lezione.classe} non è tra le tue classi"
                }
                val parti = classe.chiave.split("|", limit = 3)
                val (codice, risposta) = client.firmaRapida(parti.getOrElse(0) { "" }, parti.getOrElse(1) { "" }, lezione.materia, data, lezione.ora, log, nomeClasse = parti.getOrNull(2)?.takeIf { it.isNotBlank() } ?: classe.nome)
                check(codice in 200..299) { "classeviva ha risposto HTTP $codice ${risposta.take(120)}" }
                val (codiceOggi, grezzoOggi) = client.lezioniGrezze(data, data)
                log.append("GET lessons/mine/$data/$data -> HTTP $codiceOggi\n${grezzoOggi.take(3000)}\n\n")
                check(codiceOggi == 200) { "firma inviata, verifica non riuscita (HTTP $codiceOggi)" }
                checkNotNull(firmaCorrispondente(grezzoOggi, mioId, data, lezione, nomi)) { "firma inviata ma non trovata nel registro" }
            }
            log.append(esito.fold({ "esito: firmata (${it.materia})" }, { "esito: errore ${it.message}" }))
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !etichetta.isAttachedToWindow) return@runOnUiThread
                copiaLog.visibility = View.VISIBLE
                copiaLog.setOnClickListener {
                    activity.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("log firma rapida", log.toString()))
                    copiaLog.text = "log copiato"
                }
                esito.fold(
                    onSuccess = { risultato ->
                        mostraFirmata(etichetta, materia, risultato)
                        pulsante.visibility = View.GONE
                        (pulsante.parent as? View)?.visibility = View.GONE
                    },
                    onFailure = {
                        // solo l'esito: il motivo (risposta di classeviva) sta nel log, da copiare con "copia log"
                        etichetta.text = "firma non riuscita"
                        etichetta.setTextColor(0xFFD32F2F.toInt())
                        pulsante.isEnabled = true
                        pulsante.alpha = 1f
                    }
                )
            }
        }.start()
    }

    private data class EsitoFirma(val materia: String)

    private fun firmaCorrispondente(
        grezzo: String,
        mioId: String,
        data: String,
        lezione: Lezione,
        nomiClassi: Map<String, String>
    ): EsitoFirma? {
        val radice = org.json.JSONTokener(grezzo).nextValue()
        fun testo(o: org.json.JSONObject, vararg chiavi: String): String {
            for (k in chiavi) {
                val v = o.opt(k)
                if (v != null && v != org.json.JSONObject.NULL && v !is org.json.JSONObject && v !is org.json.JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty()) return s
                }
            }
            return ""
        }
        fun normalizza(s: String) = s.trim().replace(Regex("\\s+"), " ").uppercase(java.util.Locale.ROOT)
        fun cerca(v: Any?, livello: Int = 0): EsitoFirma? {
            if (livello > 7) return null
            when (v) {
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) cerca(v.opt(i), livello + 1)?.let { return it }
                }
                is org.json.JSONObject -> {
                    val dataFirma = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    val ora = testo(v, "evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    val durata = (testo(v, "evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                    val autore = testo(v, "authorId", "teacherId", "teacher_id")
                    val tipo = testo(v, "classType")
                    val idClasse = testo(v, "classId", "classroomId")
                    val classeFirmata = testo(v, "classDesc", "className", "classroomName", "classCode")
                        .ifEmpty { nomiClassi["$tipo|$idClasse"] ?: nomiClassi[idClasse].orEmpty() }
                    val stessaClasse = classeFirmata.isBlank() || normalizza(classeFirmata) == normalizza(lezione.classe)
                    val firmaQuestaOra = dataFirma == data && ora != null && lezione.ora in ora until (ora + durata) &&
                        (mioId.isBlank() || autore == mioId) && stessaClasse
                    if (firmaQuestaOra) {
                        val materia = testo(v, "subjectDesc", "subjectName", "subjectDescription", "subject")
                            .ifEmpty {
                                val oggettoMateria = v.optJSONObject("subject")
                                if (oggettoMateria != null) testo(oggettoMateria, "subjectDesc", "description", "name") else ""
                            }
                        return EsitoFirma(materia)
                    }
                    for (k in v.keys()) cerca(v.opt(k), livello + 1)?.let { return it }
                }
            }
            return null
        }
        return cerca(radice)
    }

    /** Ghiera che si riempie dall'inizio alla fine dell'ora (fascia dalla tabella "lezioni"); al centro i minuti che mancano. */
    private class GhieraLezione(ctx: android.content.Context, private val fascia: Fascia, carattere: Typeface) : View(ctx) {
        private val spessore = ctx.dp(7).toFloat()
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = spessore; color = 0xFFE3EEF5.toInt() }
        private val pieno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = spessore; strokeCap = Paint.Cap.ROUND; color = 0xFF69BCE8.toInt() }
        private val testo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textAlign = Paint.Align.CENTER; typeface = carattere; textSize = ctx.dp(16).toFloat() }
        private val arco = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val adesso = Calendar.getInstance()
            val secondiAdesso = (adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)) * 60 + adesso.get(Calendar.SECOND)
            val durata = ((fascia.fine - fascia.inizio) * 60).coerceAtLeast(1)
            val passati = (secondiAdesso - fascia.inizio * 60).coerceIn(0, durata)
            val residui = durata - passati
            val m = spessore / 2
            arco.set(m, m, width - m, height - m)
            canvas.drawArc(arco, 0f, 360f, false, fondo)
            canvas.drawArc(arco, -90f, 360f * passati / durata, false, pieno)
            val y = height / 2f - (testo.descent() + testo.ascent()) / 2
            canvas.drawText(String.format(java.util.Locale.ITALY, "%d:%02d", residui / 60, residui % 60), width / 2f, y, testo)
            postInvalidateDelayed(1000)
        }
    }
}
