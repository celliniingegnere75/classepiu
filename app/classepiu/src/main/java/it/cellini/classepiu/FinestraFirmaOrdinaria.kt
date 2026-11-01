package it.cellini.classepiu

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Finestra per inserire una lezione ordinaria: scelta della materia, del tipo attività e dell'argomento.
 * L'esito viene passato al chiamante, che decide come aggiornare la notifica e inviare la firma.
 */
object FinestraFirmaOrdinaria {
    @Volatile
    private var tipiAttivitaSito: List<String>? = null

    fun mostra(
        activity: Activity,
        lezione: Lezione,
        onApri: () -> Unit,
        onFirma: (civica: Boolean, argomento: String, tipoAttivita: String) -> Unit
    ) {
        onApri()
        val imp = Impostazioni(activity)
        val azzurro = 0xFF69BCE8.toInt()
        val ordinaria = android.widget.RadioButton(activity).apply {
            id = View.generateViewId()
            text = lezione.materia.lowercase().ifBlank { "materia dell'orario" }
        }
        val civica = android.widget.RadioButton(activity).apply {
            id = View.generateViewId()
            text = "educazione civica"
        }
        val sceltaMateria = android.widget.RadioGroup(activity).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            addView(ordinaria)
            addView(civica)
            check(if (imp.firmaTipo.equals("Educazione civica", ignoreCase = true)) civica.id else ordinaria.id)
        }
        // Voci del menu "Nuova lezione" del registro web, usate finché il sito non restituisce la propria lista.
        val attivita = listOf(
            "Lezione", "Spiegazione", "Interrogazione", "Interrogazione e spiegazione", "Prova pratica", "Compito in classe",
            "Verifica scritta", "Verifiche orali", "Attività di laboratorio", "Orientamento", "Didattica a distanza",
            "Video Lezione", "Video Verifica", "Video Interrogazione", "Didattica a Distanza Materiale", "Didattica a Distanza Compito",
            "Didattica a Distanza Verifica", "Video Lezione Sostegno", "Video Compresenza Sostegno", "Video Compresenza ITP",
            "Sorveglianza", "PCTO - Attività in aula", "FSL - Attività in aula", "Assemblea di classe", "Assemblea di istituto",
            "Supplenza", "PCTO - Sicurezza", "PCTO - Orientamento", "FSL - Sicurezza", "FSL - Orientamento",
            "Ore personalizzazione - attività di antidispersione", "Ore personalizzazione - attività di inclusione",
            "Ore personalizzazione - attività di orientamento", "Ore personalizzazione - attività di riorientamento",
            "Ore personalizzazione - attività di PCTO", "Ore personalizzazione - attività di apprendistato",
            "Ore personalizzazione - attività di alfabetizzazione italiano L2", "Ore personalizzazione - attività per crediti IeFP",
            "Ore personalizzazione - attività di recupero e consolidamento", "Ore personalizzazione - attività di potenziamento",
            "Ore personalizzazione - attività su metodo di studio e stili di apprendimento", "Ore personalizzazione - attività di accoglienza",
            "Ore personalizzazione - attività del bilancio personale iniziale"
        )
        var voci: List<String> = tipiAttivitaSito ?: attivita
        val tipologia = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voci)
            setSelection(0)
        }
        if (tipiAttivitaSito == null) Thread {
            val dalSito = runCatching {
                val client = imp.client().also { it.login() }
                val classe = leggiClassi(client.classiGrezze(false).second)
                    .firstOrNull { it.nome.equals(siglaClasse(lezione.classe), ignoreCase = true) }
                    ?: return@runCatching emptyList<String>()
                val parti = classe.chiave.split("|", limit = 3)
                val data = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
                client.tipiAttivita(parti.getOrElse(0) { "" }, parti.getOrElse(1) { "" }, data)
            }.getOrDefault(emptyList())
            if (dalSito.isEmpty()) return@Thread
            tipiAttivitaSito = dalSito
            activity.runOnUiThread {
                if (!tipologia.isAttachedToWindow) return@runOnUiThread
                val scelta = voci.getOrNull(tipologia.selectedItemPosition)
                voci = dalSito
                tipologia.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voci)
                tipologia.setSelection(voci.indexOf(scelta).coerceAtLeast(0))
            }
        }.start()
        val argomento = EditText(activity).apply {
            hint = "argomento della lezione"
            setText(imp.firmaArgomento)
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val corpo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(8), activity.dp(22), 0)
            addView(TextView(activity).apply { text = "materia"; setTextColor(azzurro); textSize = 14f })
            addView(sceltaMateria)
            addView(TextView(activity).apply { text = "tipo attività"; setTextColor(azzurro); textSize = 14f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(12) })
            addView(tipologia, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(activity).apply { text = "argomento"; setTextColor(azzurro); textSize = 14f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(12) })
            addView(argomento, LinearLayout.LayoutParams(-1, -2))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("firma ordinaria · ${lezione.ora}° ora, ${lezione.classe}")
            .setView(corpo)
            .setPositiveButton("firma") { _, _ ->
                onFirma(
                    sceltaMateria.checkedRadioButtonId == civica.id,
                    argomento.text.toString(),
                    voci.getOrElse(tipologia.selectedItemPosition) { "Lezione" }
                )
            }
            .setNegativeButton("annulla", null)
            .show()
    }
}
