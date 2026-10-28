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
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(20) })

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
        verificaFirma(activity, lezione, statoFirma, outfitBold)

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
        carattere: Typeface
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
                    onSuccess = { firmata ->
                        etichetta.text = if (firmata) "ora firmata" else "ora da firmare"
                        etichetta.setTextColor(if (firmata) 0xFF16803C.toInt() else 0xFFD32F2F.toInt())
                    },
                    onFailure = {
                        etichetta.text = "firma non verificata"
                        etichetta.setTextColor(0xFF8A6D00.toInt())
                    }
                )
            }
        }.start()
    }

    private fun firmaCorrispondente(
        grezzo: String,
        mioId: String,
        data: String,
        lezione: Lezione,
        nomiClassi: Map<String, String>
    ): Boolean {
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
        fun cerca(v: Any?, livello: Int = 0): Boolean {
            if (livello > 7) return false
            return when (v) {
                is org.json.JSONArray -> (0 until v.length()).any { cerca(v.opt(it), livello + 1) }
                is org.json.JSONObject -> {
                    val dataFirma = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    val ora = testo(v, "evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    val durata = (testo(v, "evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                    val autore = testo(v, "authorId", "teacherId", "teacher_id")
                    val tipo = testo(v, "classType")
                    val idClasse = testo(v, "classId", "classroomId")
                    val classeFirmata = testo(v, "classDesc", "className", "classroomName", "classCode")
                        .ifEmpty { nomiClassi["$tipo|$idClasse"] ?: nomiClassi[idClasse].orEmpty() }
                    // La firma vale anche se inserita in ritardo. Non vincolare la verifica alla materia:
                    // la descrizione può differire da quella salvata nell'orario. Se il nome della classe
                    // è disponibile, però, deve corrispondere alla lezione visualizzata.
                    val stessaClasse = classeFirmata.isBlank() || normalizza(classeFirmata) == normalizza(lezione.classe)
                    val firmaQuestaOra = dataFirma == data && ora != null && lezione.ora in ora until (ora + durata) &&
                        (mioId.isBlank() || autore == mioId) && stessaClasse
                    firmaQuestaOra || v.keys().asSequence().any { cerca(v.opt(it), livello + 1) }
                }
                else -> false
            }
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
