package com.example.sshproxy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer

class LogsFragment : Fragment() {

    private lateinit var logText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)
        logText = view.findViewById(R.id.logText)

        LogManager.logs.observe(viewLifecycleOwner, Observer { logs ->
            logText.text = logs.joinToString("\n")
            val scrollView = view.findViewById<ScrollView>(R.id.scrollView)
            scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        })

        return view
    }
}
