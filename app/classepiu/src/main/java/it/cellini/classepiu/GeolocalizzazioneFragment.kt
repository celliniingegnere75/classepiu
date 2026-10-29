package it.cellini.classepiu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Locale
import java.util.concurrent.Executors

/** Impostazioni della posizione dell'istituto e consenso alla posizione del docente. */
class GeolocalizzazioneFragment : Fragment() {
    private val azzurro = 0xFF69BCE8.toInt()
    private val bluTesto = 0xFF5598BD.toInt()
    private val nero = 0xFF222222.toInt()
    private val executor = Executors.newSingleThreadExecutor()
    private var interruttore: Switch? = null
    private var statoPermesso: TextView? = null
    private var statoIstituto: TextView? = null
    private var campoIstituto: EditText? = null

    private val richiestaPosizione = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { risultati ->
        val ctx = context ?: return@registerForActivityResult
        val concessa = risultati.values.any { it }
        ctx.getSharedPreferences("classepiu_geolocalizzazione", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("posizione_docente_attiva", concessa).apply()
        aggiornaPermesso()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pref = ctx.getSharedPreferences("classepiu_geolocalizzazione", android.content.Context.MODE_PRIVATE)
        val radice = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(ctx.dp(18), ctx.dp(24), ctx.dp(18), ctx.dp(100))
        }
        radice.addView(TextView(ctx).apply {
            text = "geolocalizzazione"
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(nero)
        }, piena())
        radice.addView(TextView(ctx).apply {
            text = "Configura la posizione dell'istituto e scegli se consentire a ClassePiù di accedere alla posizione del docente."
            textSize = 14f
            setTextColor(0xFF777777.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(18) })

        val schedaIstituto = card(ctx)
        schedaIstituto.addView(titolo(ctx, "istituto"))
        schedaIstituto.addView(TextView(ctx).apply {
            text = "Inserisci il nome completo o l'indirizzo dell'edificio scolastico. La ricerca salva le coordinate per le funzioni basate sulla distanza."
            textSize = 13f
            setTextColor(0xFF777777.toInt())
        }, piena().apply { topMargin = ctx.dp(6) })
        campoIstituto = EditText(ctx).apply {
            hint = "Nome o indirizzo dell'istituto"
            textSize = 15f
            isSingleLine = false
            minLines = 1
            maxLines = 3
            setText(pref.getString("query", "") ?: "")
            setTextColor(nero)
            background = GradientDrawable().apply {
                setColor(0xFFF7FAFC.toInt())
                cornerRadius = ctx.dp(12).toFloat()
                setStroke(ctx.dp(1), 0xFFD7E6EF.toInt())
            }
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        }
        schedaIstituto.addView(campoIstituto, piena().apply { topMargin = ctx.dp(12) })
        val cerca = pillola(ctx, "cerca istituto", azzurro, Color.WHITE)
        schedaIstituto.addView(cerca, LinearLayout.LayoutParams(-2, ctx.dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(12) })
        statoIstituto = TextView(ctx).apply {
            textSize = 13f
            setTextColor(bluTesto)
            text = coordinateSalvate(pref)
        }
        schedaIstituto.addView(statoIstituto, piena().apply { topMargin = ctx.dp(10) })
        cerca.setOnClickListener {
            val query = campoIstituto?.text?.toString()?.trim().orEmpty()
            if (query.isBlank()) {
                Toast.makeText(ctx, "Inserisci il nome o l'indirizzo dell'istituto.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pref.edit().putString("query", query).apply()
            cerca.isEnabled = false
            cerca.text = "ricerca…"
            executor.execute {
                val risultato = runCatching {
                    if (!Geocoder.isPresent()) throw IllegalStateException("Servizio di ricerca indirizzi non disponibile su questo dispositivo.")
                    @Suppress("DEPRECATION")
                    Geocoder(ctx, Locale.ITALY).getFromLocationName(query, 1)?.firstOrNull()
                        ?: throw IllegalStateException("Istituto non trovato. Prova con indirizzo, comune e provincia.")
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    cerca.isEnabled = true
                    cerca.text = "cerca istituto"
                    risultato.fold(
                        onSuccess = { luogo ->
                            pref.edit().putString("nome", query).putString("indirizzo", luogo.getAddressLine(0) ?: query)
                                .putString("latitudine", luogo.latitude.toString())
                                .putString("longitudine", luogo.longitude.toString()).apply()
                            statoIstituto?.text = "istituto salvato\n${luogo.getAddressLine(0) ?: query}\n${"%.5f".format(Locale.ITALY, luogo.latitude)}, ${"%.5f".format(Locale.ITALY, luogo.longitude)}"
                            Toast.makeText(ctx, "Posizione dell'istituto salvata.", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { errore ->
                            statoIstituto?.text = errore.message ?: "Ricerca non riuscita."
                            statoIstituto?.setTextColor(0xFFD32F2F.toInt())
                        }
                    )
                }
            }
        }
        radice.addView(schedaIstituto, piena().apply { bottomMargin = ctx.dp(16) })

        val schedaDocente = card(ctx)
        schedaDocente.addView(titolo(ctx, "posizione del docente"))
        schedaDocente.addView(TextView(ctx).apply {
            text = "Consenti a ClassePiù di usare la posizione del dispositivo mentre l'app è in uso. Il permesso viene richiesto solo dopo aver attivato l'interruttore."
            textSize = 13f
            setTextColor(0xFF777777.toInt())
        }, piena().apply { topMargin = ctx.dp(6) })
        val rigaPermesso = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statoPermesso = TextView(ctx).apply { textSize = 14f; setTextColor(nero) }
        rigaPermesso.addView(statoPermesso, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ctx.dp(8) })
        interruttore = Switch(ctx).apply {
            buttonTintList = null
            isChecked = pref.getBoolean("posizione_docente_attiva", false) && haPermesso()
            setOnCheckedChangeListener { _, acceso ->
                if (acceso) {
                    if (haPermesso()) {
                        pref.edit().putBoolean("posizione_docente_attiva", true).apply()
                        aggiornaPermesso()
                    } else {
                        richiestaPosizione.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                } else {
                    // Disattiva solo l'uso interno dell'app: il permesso Android resta invariato.
                    pref.edit().putBoolean("posizione_docente_attiva", false).apply()
                    aggiornaPermesso()
                }
            }
        }
        rigaPermesso.addView(interruttore)
        schedaDocente.addView(rigaPermesso, piena().apply { topMargin = ctx.dp(12) })
        radice.addView(schedaDocente, piena())
        aggiornaPermesso()
        return ScrollView(ctx).apply {
            clipToPadding = false
            addView(radice)
        }
    }

    override fun onResume() {
        super.onResume()
        aggiornaPermesso()
    }

    private fun haPermesso(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun aggiornaPermesso() {
        val ctx = context ?: return
        val pref = ctx.getSharedPreferences("classepiu_geolocalizzazione", android.content.Context.MODE_PRIVATE)
        val consentito = haPermesso()
        var attivo = pref.getBoolean("posizione_docente_attiva", false)
        if (!consentito && attivo) {
            pref.edit().putBoolean("posizione_docente_attiva", false).apply()
            attivo = false
        }
        statoPermesso?.text = when {
            attivo && consentito -> "uso interno della posizione attivo"
            consentito -> "uso interno disattivato; permesso Android disponibile"
            else -> "uso interno della posizione disattivato"
        }
        statoPermesso?.setTextColor(if (attivo && consentito) 0xFF16803C.toInt() else 0xFF777777.toInt())
        interruttore?.let { sw ->
            if (sw.isChecked != (attivo && consentito)) {
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = attivo && consentito
                sw.setOnCheckedChangeListener { _, acceso ->
                    if (acceso) {
                        if (haPermesso()) {
                            pref.edit().putBoolean("posizione_docente_attiva", true).apply()
                            aggiornaPermesso()
                        } else {
                            richiestaPosizione.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    } else {
                        pref.edit().putBoolean("posizione_docente_attiva", false).apply()
                        aggiornaPermesso()
                    }
                }
            }
        }
    }

    private fun coordinateSalvate(pref: android.content.SharedPreferences): String {
        val lat = pref.getString("latitudine", null) ?: return "nessuna posizione salvata"
        val lon = pref.getString("longitudine", null) ?: return "nessuna posizione salvata"
        return "posizione salvata: $lat, $lon"
    }

    private fun piena() = LinearLayout.LayoutParams(-1, -2)
    private fun card(ctx: android.content.Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = ctx.dp(18).toFloat()
            setStroke(ctx.dp(1), 0xFFE3EDF3.toInt())
        }
        elevation = ctx.dp(2).toFloat()
    }
    private fun titolo(ctx: android.content.Context, testo: String) = TextView(ctx).apply {
        text = testo
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(bluTesto)
    }
    private fun pillola(ctx: android.content.Context, testo: String, fondo: Int, testoColore: Int) = TextView(ctx).apply {
        text = testo
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(testoColore)
        setPadding(ctx.dp(22), 0, ctx.dp(22), 0)
        background = GradientDrawable().apply {
            setColor(fondo)
            cornerRadius = ctx.dp(22).toFloat()
        }
        isClickable = true
        isFocusable = true
    }
}
