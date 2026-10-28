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
        // Riga superiore: aula ben distinta dal codice classe, con spaziatura più ampia.
        radice.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (lezione.aula.isNotBlank()) {
                // Etichetta "aula" sopra al numero, disposto su una seconda riga.
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(TextView(activity).apply {
                        text = "aula"
                        textSize = 16f
                        typeface = outfitRegular
                        setTextColor(azzurro)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setSingleLine(true)
                    })
                    addView(TextView(activity).apply {
                        text = lezione.aula
                        textSize = 22f
                        typeface = outfitBold
                        setTextColor(azzurro)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setSingleLine(true)
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 0 })
                })
            }
            addView(TextView(activity).apply {
                text = lezione.classe
                textSize = 22f
                typeface = outfitBold
                setTextColor(celesteClasse)
                setSingleLine(true)
            }, LinearLayout.LayoutParams(-2, -2).apply { if (lezione.aula.isNotBlank()) leftMargin = activity.dp(52) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(28) })

        // Ghiera sotto la riga aula/classe, con margine uniforme.
        radice.addView(GhieraLezione(activity, fascia, outfitBold), LinearLayout.LayoutParams(activity.dp(87), activity.dp(87)).apply {
            topMargin = activity.dp(28)
        })

        // Materia in una sola riga, con ellissi e carattere dimezzato rispetto a prima.
        radice.addView(TextView(activity).apply {
            text = lezione.materia
            textSize = 7.5f
            typeface = outfitRegular
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })

        // Stato firma: viene verificato realmente su ClasseViva, senza bloccare l'apertura della finestra.
        val statoFirma = TextView(activity).apply {
            text = "verifico la firma…"
            textSize = 14f
            typeface = outfitBold
            gravity = Gravity.CENTER
            setTextColor(0xFF777777.toInt())
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(12))
        }
        radice.addView(statoFirma, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(21) })
        // compare solo se l'ora non è firmata: firma tipologia "lezione" con argomento vuoto
        val firmaRapida = TextView(activity).apply {
            text = "firma rapida"
            textSize = 15f
            typeface = outfitBold
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(azzurro)
                cornerRadius = activity.dp(22).toFloat()
            }
            setPadding(activity.dp(28), activity.dp(10), activity.dp(28), activity.dp(10))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        radice.addView(firmaRapida, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = activity.dp(24) })
        verificaFirma(activity, lezione, statoFirma, firmaRapida)

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(radice)
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
        radice.alpha = 0f
        radice.scaleX = .92f
        radice.scaleY = .92f
        radice.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
        handler.postDelayed({
            if (dialog === d) {
                radice.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(220).withEndAction {
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
        pulsante: TextView
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
                            pulsante.visibility = View.VISIBLE
                            pulsante.setOnClickListener { firmaRapida(activity, lezione, etichetta, pulsante) }
                        } else {
                            mostraFirmata(etichetta, risultato)
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

    private fun mostraFirmata(etichetta: TextView, risultato: EsitoFirma) {
        val materiaRegistro = risultato.materia
        etichetta.text = if (materiaRegistro.isNotBlank()) "ora firmata\n$materiaRegistro" else "ora firmata"
        etichetta.setTextColor(0xFF16803C.toInt())
    }

    /**
     * Firma rapida dell'ora in corso: copia classe e materia da una mia firma degli ultimi 60 giorni (lessons/mine),
     * la manda come nuova firma "Lezione" senza argomento (PUT lessons/sigs) e poi rilegge le firme di oggi per conferma.
     * La finestra resta aperta finché non si chiude con la X, così l'esito si legge.
     */
    private fun firmaRapida(activity: Activity, lezione: Lezione, etichetta: TextView, pulsante: TextView) {
        handler.removeCallbacksAndMessages(null)
        pulsante.isEnabled = false
        pulsante.alpha = .5f
        etichetta.text = "firmo…"
        etichetta.setTextColor(0xFF777777.toInt())
        val formato = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
        val oggi = java.util.Date()
        val data = formato.format(oggi)
        val da = formato.format(java.util.Date(oggi.time - 60L * 24 * 3600 * 1000))
        Thread {
            val esito = runCatching {
                val client = Impostazioni(activity).client().also { it.login() }
                val mioId = client.idDocente()
                val (codiceClassi, classiGrezze) = client.classiGrezze(false)
                val nomi = if (codiceClassi == 200) OrarioDalleFirme.nomiClassi(leggiClassi(classiGrezze)) else emptyMap()
                val (codiceStorico, storico) = client.lezioniGrezze(da, data)
                check(codiceStorico == 200) { "firme precedenti non disponibili (HTTP $codiceStorico)" }
                // controllo di nuovo: se nel frattempo l'ora è stata firmata non si firma due volte
                firmaCorrispondente(storico, mioId, data, lezione, nomi)?.let { return@runCatching it }
                val modello = checkNotNull(modelloFirma(storico, mioId, lezione, nomi)) { "nessuna tua firma precedente per ${lezione.classe}" }
                val (codice, risposta) = client.firmaRapida(modello, data, lezione.ora)
                check(codice in 200..299) { "classeviva ha risposto HTTP $codice ${risposta.take(120)}" }
                val (codiceOggi, grezzoOggi) = client.lezioniGrezze(data, data)
                check(codiceOggi == 200) { "firma inviata, verifica non riuscita (HTTP $codiceOggi)" }
                checkNotNull(firmaCorrispondente(grezzoOggi, mioId, data, lezione, nomi)) { "firma inviata ma non trovata nel registro" }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !etichetta.isAttachedToWindow) return@runOnUiThread
                esito.fold(
                    onSuccess = { risultato ->
                        mostraFirmata(etichetta, risultato)
                        pulsante.visibility = View.GONE
                    },
                    onFailure = { e ->
                        etichetta.text = "firma non riuscita\n${(e.message ?: "errore").lowercase()}"
                        etichetta.setTextColor(0xFFD32F2F.toInt())
                        pulsante.isEnabled = true
                        pulsante.alpha = 1f
                    }
                )
            }
        }.start()
    }

    /**
     * La mia firma più recente della stessa classe e materia, da usare come modello per la firma rapida:
     * meglio una di tipologia "Lezione"; materia confrontata col nome registrato nell'orario.
     */
    private fun modelloFirma(grezzo: String, mioId: String, lezione: Lezione, nomiClassi: Map<String, String>): org.json.JSONObject? {
        fun normalizza(s: String) = s.trim().replace(Regex("\\s+"), " ").uppercase(java.util.Locale.ROOT)
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
        val candidate = ArrayList<org.json.JSONObject>()
        fun raccogli(v: Any?, livello: Int = 0) {
            if (livello > 7) return
            when (v) {
                is org.json.JSONArray -> for (i in 0 until v.length()) raccogli(v.opt(i), livello + 1)
                is org.json.JSONObject -> {
                    if (v.has("evtHPos") && v.has("classId")) candidate.add(v)
                    else for (k in v.keys()) raccogli(v.opt(k), livello + 1)
                }
            }
        }
        raccogli(org.json.JSONTokener(grezzo).nextValue())
        val adatte = candidate.filter { v ->
            val autore = testo(v, "authorId", "teacherId", "teacher_id")
            val tipo = testo(v, "classType")
            val idClasse = testo(v, "classId", "classroomId")
            val classe = testo(v, "classDesc", "className", "classroomName", "classCode")
                .ifEmpty { nomiClassi["$tipo|$idClasse"] ?: nomiClassi[idClasse].orEmpty() }
            val materia = testo(v, "subjectDesc", "subjectName", "subjectDescription")
            (mioId.isBlank() || autore == mioId) &&
                normalizza(classe) == normalizza(lezione.classe) &&
                (lezione.materia.isBlank() || normalizza(materia) == normalizza(lezione.materia))
        }
        return adatte.sortedWith(
            compareByDescending<org.json.JSONObject> { testo(it, "lessonType").equals("Lezione", ignoreCase = true) }
                .thenByDescending { testo(it, "evtDate") }
        ).firstOrNull()
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
