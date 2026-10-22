package it.cellini.classepiu

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class BachecaFragment : Fragment() {

    private var tutte: List<Circolare> = emptyList()
    private var filtro = 0 // 0 tutte, 1 da leggere, 2 da confermare
    private var cerca = ""
    private lateinit var elenco: RecyclerView
    private lateinit var aggiorna: SwipeRefreshLayout
    private lateinit var vuoto: TextView
    private val adattatore = Adattatore()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val testa = ctx.colonna(12).apply { setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), 0) }

        val campoCerca = TextInputEditText(ctx).apply {
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { cerca = s?.toString().orEmpty(); mostra() }
            })
        }
        val layoutCerca = TextInputLayout(ctx).apply { hint = "Cerca nelle circolari"; addView(campoCerca) }

        val chips = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf("Tutte", "Da leggere", "Da confermare").forEachIndexed { i, s ->
            chips.addView(Chip(ctx).apply {
                text = s; isCheckable = true; isChecked = i == 0; id = View.generateViewId()
                setOnClickListener { filtro = i; mostra() }
            })
        }
        testa.aggiungi(layoutCerca, chips)

        elenco = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = adattatore
            clipToPadding = false
            setPadding(0, 0, 0, ctx.dp(88))
        }
        aggiorna = SwipeRefreshLayout(ctx).apply {
            addView(elenco)
            setOnRefreshListener { carica() }
        }
        vuoto = ctx.testo("", 15f, alpha = .7f).apply {
            setPadding(ctx.dp(24), ctx.dp(40), ctx.dp(24), 0)
            visibility = View.GONE
        }
        val corpo = FrameLayout(ctx).apply {
            addView(aggiorna)
            addView(vuoto)
        }
        val fab = ExtendedFloatingActionButton(ctx).apply {
            text = "Controlla e invia"
            setIconResource(android.R.drawable.ic_dialog_email)
            setOnClickListener { controllaOra(this) }
        }
        val radice = FrameLayout(ctx)
        val colonna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(testa)
            addView(corpo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        radice.addView(colonna)
        radice.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(0, 0, ctx.dp(16), ctx.dp(16)) })

        tutte = leggiCache()
        mostra()
        carica()
        return radice
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && tutte.isEmpty()) carica()
    }

    private fun cache() = File(requireContext().filesDir, "bacheca.json")

    private fun leggiCache(): List<Circolare> = try {
        val a = JSONArray(cache().readText())
        (0 until a.length()).map { circolareDa(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun carica() {
        val ctx = context ?: return
        val imp = Impostazioni(ctx)
        if (!imp.pronta) {
            aggiorna.isRefreshing = false
            vuoto.text = "Inserisci utente e password ClasseViva nella scheda Impostazioni."
            vuoto.visibility = View.VISIBLE
            return
        }
        aggiorna.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val esito = withContext(Dispatchers.IO) {
                runCatching {
                    val cv = imp.client()
                    cv.login()
                    cv.bacheca()
                }
            }
            aggiorna.isRefreshing = false
            esito.onSuccess { lista ->
                tutte = lista
                runCatching { cache().writeText(JSONArray().apply { lista.forEach { put(it.json()) } }.toString()) }
                mostra()
            }.onFailure { e ->
                view?.let { Snackbar.make(it, e.message ?: "Errore", Snackbar.LENGTH_LONG).show() }
                mostra()
            }
        }
    }

    private fun controllaOra(v: View) {
        val ctx = requireContext()
        v.isEnabled = false
        Snackbar.make(v, "Controllo in corso…", Snackbar.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val esito = withContext(Dispatchers.IO) { runCatching { Controllo.esegui(ctx.applicationContext) } }
            v.isEnabled = true
            val msg = esito.fold({ it.messaggio + if (!Impostazioni(ctx).mailPronta) " (mail non configurata)" else "" }, { "Errore: ${it.message}" })
            view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_LONG).show() }
            carica()
        }
    }

    private fun mostra() {
        val q = cerca.trim().lowercase()
        val vis = tutte.filter {
            when (filtro) {
                1 -> !it.letta
                2 -> it.azioni.isNotEmpty() || it.adesione
                else -> true
            } && (q.isEmpty() || it.titolo.lowercase().contains(q) || it.testo.lowercase().contains(q) || it.categoria.lowercase().contains(q))
        }
        adattatore.dati = vis
        adattatore.notifyDataSetChanged()
        if (::vuoto.isInitialized && Impostazioni(requireContext()).pronta) {
            vuoto.text = if (tutte.isEmpty()) "Nessuna circolare. Trascina in basso per aggiornare." else "Nessuna circolare con questo filtro."
            vuoto.visibility = if (vis.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class Adattatore : RecyclerView.Adapter<Adattatore.VH>() {
        var dati: List<Circolare> = emptyList()

        inner class VH(val box: LinearLayout, val titolo: TextView, val info: TextView, val badge: TextView) : RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val titolo = ctx.testo("", 15.5f, true)
            val info = ctx.testo("", 13f, alpha = .7f)
            val badge = ctx.testo("", 12f, true).apply { setTextColor(ContextCompat.getColor(ctx, R.color.azione)) }
            val box = ctx.colonna(0).apply {
                setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                aggiungi(badge, titolo, info)
            }
            return VH(box, titolo, info, badge)
        }

        override fun getItemCount() = dati.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = dati[pos]
            h.titolo.text = (if (!c.letta) "● " else "") + c.titolo
            val parti = mutableListOf(dataBreve(c.data))
            if (c.categoria.isNotBlank()) parti += c.categoria
            if (c.allegati.isNotEmpty()) parti += "${c.allegati.size} allegat" + if (c.allegati.size == 1) "o" else "i"
            h.info.text = parti.joinToString(" · ")
            val badge = (c.azioni.map { it.uppercase() } + if (c.adesione) listOf("ADESIONE") else emptyList()).joinToString(" · ")
            h.badge.text = if (badge.isNotEmpty()) "DA FARE: $badge" else ""
            h.badge.visibility = if (badge.isNotEmpty()) View.VISIBLE else View.GONE
            h.titolo.alpha = if (c.letta) 0.75f else 1f
            h.box.setOnClickListener {
                startActivity(Intent(requireContext(), DettaglioActivity::class.java).putExtra("circolare", c.json().toString()))
            }
        }
    }
}

/** "2026-10-21" -> "21/10/2026" */
fun dataBreve(s: String): String {
    val d = s.take(10).split('-')
    return if (d.size == 3) "${d[2]}/${d[1]}/${d[0]}" else s
}
