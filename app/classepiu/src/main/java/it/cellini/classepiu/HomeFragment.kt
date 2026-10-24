package it.cellini.classepiu

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/** Scheda home: per ora vuota (l'orologio sta nella fascia azzurra in alto). */
class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { setBackgroundColor(Color.WHITE) }
}
