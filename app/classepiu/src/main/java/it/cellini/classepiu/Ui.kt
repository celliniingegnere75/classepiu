package it.cellini.classepiu

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.colonna(padding: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(padding)
    setPadding(p, p, p, p)
}

fun Context.riga(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

fun Context.testo(s: CharSequence, size: Float = 15f, grassetto: Boolean = false, alpha: Float = 1f): TextView = TextView(this).apply {
    text = s
    textSize = size
    if (grassetto) setTypeface(typeface, Typeface.BOLD)
    this.alpha = alpha
    setTextIsSelectable(false)
}

fun Context.titoloSezione(s: String): TextView = testo(s, 17f, true).apply {
    setPadding(0, dp(20), 0, dp(6))
}

fun Context.bottone(s: String, pieno: Boolean = true, click: (View) -> Unit): MaterialButton {
    val b = if (pieno) MaterialButton(this) else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    b.text = s
    b.isAllCaps = false
    b.setOnClickListener(click)
    return b
}

/** Campo di testo con etichetta. */
class Campo(ctx: Context, etichetta: String, password: Boolean = false, email: Boolean = false) {
    val edit = TextInputEditText(ctx)
    val layout = TextInputLayout(ctx).apply {
        hint = etichetta
        if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        addView(edit)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(6)
        }
    }

    init {
        edit.inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            else -> InputType.TYPE_CLASS_TEXT
        }
        edit.setSingleLine(true)
    }

    var valore: String
        get() = edit.text?.toString() ?: ""
        set(v) = edit.setText(v)
}

fun ViewGroup.aggiungi(vararg viste: View) = viste.forEach { addView(it) }

fun piena(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

// ---- conversione circolari <-> JSON (per passarle al dettaglio e tenerle in memoria offline)

fun Circolare.json(): JSONObject = JSONObject()
    .put("evtCode", evtCode).put("pubId", pubId).put("titolo", titolo).put("data", data)
    .put("categoria", categoria).put("testo", testo).put("letta", letta).put("firma", firma)
    .put("risposta", risposta).put("file", file).put("adesione", adesione)
    .put("allegati", JSONArray().apply { allegati.forEach { put(JSONObject().put("n", it.numero).put("nome", it.nome)) } })

fun circolareDa(o: JSONObject): Circolare {
    val a = o.optJSONArray("allegati") ?: JSONArray()
    return Circolare(
        o.optString("evtCode"), o.optString("pubId"), o.optString("titolo"), o.optString("data"),
        o.optString("categoria"), o.optString("testo"), o.optBoolean("letta"), o.optBoolean("firma"),
        o.optBoolean("risposta"), o.optBoolean("file"), o.optBoolean("adesione"),
        (0 until a.length()).map { Allegato(a.getJSONObject(it).optInt("n"), a.getJSONObject(it).optString("nome")) },
    )
}
