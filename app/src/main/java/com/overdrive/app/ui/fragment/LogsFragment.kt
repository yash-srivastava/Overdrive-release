package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.R
import com.overdrive.app.ui.adapter.LogsAdapter
import com.overdrive.app.ui.viewmodel.LogsViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {

    private val logsViewModel: LogsViewModel by activityViewModels()

    private lateinit var recyclerLogs: RecyclerView
    private lateinit var spinnerFilter: Spinner
    private lateinit var btnClearLogs: ImageButton
    private lateinit var btnExportLogs: ImageButton

    private val logsAdapter = LogsAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingUpdate: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerLogs = view.findViewById(R.id.recyclerLogs)
        spinnerFilter = view.findViewById(R.id.spinnerFilter)
        btnClearLogs = view.findViewById(R.id.btnClearLogs)
        btnExportLogs = view.findViewById(R.id.btnExportLogs)

        recyclerLogs.layoutManager = LinearLayoutManager(context)
        recyclerLogs.adapter = logsAdapter

        val filters = listOf("All", "Camera", "Sentry", "Proxy", "Tunnel", "System")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, filters)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilter.adapter = spinnerAdapter

        logsViewModel.filter.value?.let { active ->
            val idx = filters.indexOf(active)
            if (idx >= 0) spinnerFilter.setSelection(idx)
        }

        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                logsViewModel.setFilter(if (position == 0) null else filters[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnClearLogs.setOnClickListener {
            logsViewModel.clearLogs()
        }

        btnExportLogs.setOnClickListener {
            exportLogs()
        }

        logsViewModel.filteredLogs.observe(viewLifecycleOwner) { logs ->
            pendingUpdate?.let { handler.removeCallbacks(it) }
            val update = Runnable {
                val reversed = logs.reversed()
                logsAdapter.submitList(reversed) {
                    if (reversed.isNotEmpty()) recyclerLogs.scrollToPosition(0)
                }
            }
            pendingUpdate = update
            handler.postDelayed(update, 150)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingUpdate?.let { handler.removeCallbacks(it) }
    }

    private fun exportLogs() {
        val logs = logsViewModel.filteredLogs.value ?: emptyList()
        if (logs.isEmpty()) {
            Toast.makeText(requireContext(), "No logs to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val now = Date()
            val fileTs = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            val humanTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
            val fileName = "overdrive_logs_$fileTs.txt"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)

            val sb = StringBuilder()
            sb.appendLine("=== Overdrive Dashcam Logs ===")
            sb.appendLine("Exported : $humanTs")
            sb.appendLine("Entries  : ${logs.size}")
            sb.appendLine("=".repeat(40))
            sb.appendLine()
            logs.forEach { entry ->
                val level = entry.level.name.padEnd(5)
                sb.appendLine("[${entry.formattedTime}]  $level  [${entry.tag}]  ${entry.message}")
            }
            file.writeText(sb.toString())

            Toast.makeText(requireContext(), "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
