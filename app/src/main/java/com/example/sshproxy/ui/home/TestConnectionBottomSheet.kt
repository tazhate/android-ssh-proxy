package com.example.sshproxy.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.sshproxy.R
import com.example.sshproxy.SshProxyService
import com.example.sshproxy.data.ConnectionState
import com.example.sshproxy.network.ConnectionQuality
import com.example.sshproxy.network.HttpLatencyTester
import kotlinx.coroutines.launch

class TestConnectionBottomSheet(private val viewModel: HomeViewModel) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_test_connection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTestStatus = view.findViewById<TextView>(R.id.tvTestStatus)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val layoutResults = view.findViewById<LinearLayout>(R.id.layoutResults)
        val layoutTestResults = view.findViewById<LinearLayout>(R.id.layoutTestResults)
        val viewOverallIndicator = view.findViewById<View>(R.id.viewOverallIndicator)
        val tvOverallResult = view.findViewById<TextView>(R.id.tvOverallResult)
        val btnClose = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)

        btnClose.setOnClickListener { dismiss() }

        lifecycleScope.launch {
            try {
                // Create HTTP latency tester
                val selectedServer = viewModel.selectedServer.value
                val isVpnActive = SshProxyService.connectionState.value == SshProxyService.ConnectionState.CONNECTED
                val latencyTester = if (isVpnActive) {
                    HttpLatencyTester(
                        proxyHost = "127.0.0.1",
                        proxyPort = selectedServer?.httpProxyPort ?: 8080,
                        timeoutMs = 10000
                    )
                } else {
                    HttpLatencyTester(
                        proxyHost = null,
                        proxyPort = null,
                        timeoutMs = 10000
                    )
                }

                // Start testing
                tvTestStatus.text = getString(R.string.test_connection_checking)
                progressBar.progress = 10

                val result = latencyTester.performSingleTest()
                
                // Update progress
                progressBar.progress = 100
                tvTestStatus.text = getString(R.string.test_connection_completed)
                
                // Show results after a short delay
                kotlinx.coroutines.delay(500)
                layoutResults.visibility = View.VISIBLE
                
                // Clear any existing results
                layoutTestResults.removeAllViews()
                
                // Add individual test results
                result.individualResults.forEach { testResult ->
                    val resultView = createTestResultView(testResult)
                    layoutTestResults.addView(resultView)
                }
                
                // Determine overall quality
                val overallQuality = when {
                    result.successRate < 50f -> ConnectionQuality.POOR
                    result.averageLatencyMs > 1500 -> ConnectionQuality.POOR
                    result.averageLatencyMs > 1000 -> ConnectionQuality.FAIR  
                    result.averageLatencyMs > 600 -> ConnectionQuality.GOOD
                    else -> ConnectionQuality.EXCELLENT
                }
                
                // Update overall result
                viewOverallIndicator.setBackgroundColor(overallQuality.color)
                tvOverallResult.text = "${overallQuality.getDisplayName(requireContext())} (${result.averageLatencyMs}ms)"
                tvOverallResult.setTextColor(overallQuality.color)

            } catch (e: Exception) {
                progressBar.progress = 100
                tvTestStatus.text = getString(R.string.test_connection_error)
                layoutResults.visibility = View.VISIBLE
                
                val errorView = TextView(requireContext()).apply {
                    text = "Ошибка: ${e.message ?: "Неизвестная ошибка"}"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
                    setPadding(16, 8, 16, 8)
                }
                layoutTestResults.addView(errorView)
                
                viewOverallIndicator.setBackgroundColor(ConnectionQuality.POOR.color)
                tvOverallResult.text = "Ошибка теста"
                tvOverallResult.setTextColor(ConnectionQuality.POOR.color)
            }
        }
    }

    private fun createTestResultView(testResult: com.example.sshproxy.network.HttpLatencyResult): View {
        val resultLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        // Status indicator
        val indicator = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(8, 8).apply {
                setMargins(0, 0, 12, 0)
            }
            background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_shape)
            setBackgroundColor(
                if (testResult.isSuccessful) {
                    when {
                        testResult.latencyMs > 1500 -> ContextCompat.getColor(requireContext(), R.color.error)
                        testResult.latencyMs > 1000 -> ContextCompat.getColor(requireContext(), R.color.warning) 
                        testResult.latencyMs > 600 -> ContextCompat.getColor(requireContext(), R.color.good)
                        else -> ContextCompat.getColor(requireContext(), R.color.good)
                    }
                } else {
                    ContextCompat.getColor(requireContext(), R.color.error)
                }
            )
        }

        // URL and result text
        val textView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            val hostname = testResult.url.replace("https://", "").replace("http://", "").split("/")[0]
            text = if (testResult.isSuccessful) {
                "$hostname: ${testResult.latencyMs}ms"
            } else {
                "$hostname: ${testResult.errorMessage ?: "Ошибка"}"
            }
            setTextColor(
                if (testResult.isSuccessful) {
                    // Get color from current theme
                    val typedValue = android.util.TypedValue()
                    requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
                    typedValue.data
                } else {
                    ContextCompat.getColor(requireContext(), R.color.error)
                }
            )
        }

        resultLayout.addView(indicator)
        resultLayout.addView(textView)
        
        return resultLayout
    }

    companion object {
        const val TAG = "TestConnectionBottomSheet"
    }
}