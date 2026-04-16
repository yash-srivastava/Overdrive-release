package com.overdrive.app.ui.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R

/**
 * Adapter for displaying daemon cards in a RecyclerView.
 */
class DaemonAdapter(
    private val onToggle: (DaemonType, Boolean) -> Unit,
    private val onConfigureClick: ((DaemonType) -> Unit)? = null,
    private val onDownloadLog: ((DaemonType) -> Unit)? = null
) : ListAdapter<DaemonState, DaemonAdapter.DaemonViewHolder>(DaemonDiffCallback()) {
    
    // Track expanded states
    private val expandedStates = mutableMapOf<DaemonType, Boolean>()
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DaemonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daemon_card, parent, false)
        return DaemonViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DaemonViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class DaemonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val statusGlow: View? = itemView.findViewById(R.id.statusGlow)
        private val tvDaemonName: TextView = itemView.findViewById(R.id.tvDaemonName)
        private val tvDaemonStatus: TextView = itemView.findViewById(R.id.tvDaemonStatus)
        private val switchDaemon: SwitchMaterial = itemView.findViewById(R.id.switchDaemon)
        private val ivExpand: ImageView = itemView.findViewById(R.id.ivExpand)
        private val ivConfigure: ImageView = itemView.findViewById(R.id.ivConfigure)
        private val ivDownloadLog: ImageView = itemView.findViewById(R.id.ivDownloadLog)
        private val mainRow: View = itemView.findViewById(R.id.mainRow)
        private val subprocessContainer: View = itemView.findViewById(R.id.subprocessContainer)
        private val subprocessList: LinearLayout = itemView.findViewById(R.id.subprocessList)
        
        fun bind(state: DaemonState) {
            tvDaemonName.text = getDaemonDisplayName(state.type)
            
            // Build status text with uptime
            val statusText = when {
                state.needsConfiguration -> state.configurationMessage ?: "Configuration required"
                state.status == DaemonStatus.RUNNING -> {
                    val uptimeStr = state.uptime
                    if (!uptimeStr.isNullOrEmpty()) {
                        "Running • Uptime: $uptimeStr"
                    } else {
                        "Running"
                    }
                }
                state.status == DaemonStatus.STOPPED -> "Stopped"
                state.status == DaemonStatus.STARTING -> state.statusText.ifEmpty { "Starting..." }
                state.status == DaemonStatus.STOPPING -> state.statusText.ifEmpty { "Stopping..." }
                state.status == DaemonStatus.ERROR -> state.statusText.ifEmpty { "Error" }
                else -> state.statusText
            }
            tvDaemonStatus.text = statusText
            
            // Set status indicator color
            val colorRes = when {
                state.needsConfiguration -> R.color.status_starting // Yellow/amber for needs config
                state.status == DaemonStatus.RUNNING -> R.color.status_running
                state.status == DaemonStatus.STOPPED -> R.color.status_stopped
                state.status == DaemonStatus.ERROR -> R.color.status_error
                else -> R.color.status_starting
            }
            val color = ContextCompat.getColor(itemView.context, colorRes)
            (statusIndicator.background as? GradientDrawable)?.setColor(color)
            
            // Update glow visibility and color
            statusGlow?.apply {
                visibility = if (state.status == DaemonStatus.RUNNING) View.VISIBLE else View.INVISIBLE
                alpha = if (state.status == DaemonStatus.RUNNING) 0.5f else 0f
            }
            
            // Set status text color based on status
            val textColorRes = when {
                state.needsConfiguration -> R.color.status_starting // Yellow/amber
                state.status == DaemonStatus.RUNNING -> R.color.status_running
                state.status == DaemonStatus.ERROR -> R.color.status_error
                state.status == DaemonStatus.STARTING || state.status == DaemonStatus.STOPPING -> R.color.status_starting
                else -> R.color.text_secondary
            }
            tvDaemonStatus.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))
            
            // Set switch state without triggering listener
            switchDaemon.setOnCheckedChangeListener(null)
            switchDaemon.isChecked = state.status == DaemonStatus.RUNNING
            switchDaemon.isEnabled = !state.needsConfiguration && 
                                     state.status != DaemonStatus.STARTING && 
                                     state.status != DaemonStatus.STOPPING
            
            // Set listener
            switchDaemon.setOnCheckedChangeListener { _, isChecked ->
                onToggle(state.type, isChecked)
            }
            
            // Handle subprocess expansion
            val hasSubprocesses = state.subprocesses.isNotEmpty()
            ivExpand.visibility = if (hasSubprocesses) View.VISIBLE else View.GONE
            
            val isExpanded = expandedStates[state.type] == true
            subprocessContainer.visibility = if (isExpanded && hasSubprocesses) View.VISIBLE else View.GONE
            ivExpand.rotation = if (isExpanded) 180f else 0f
            
            // Show configure icon for configurable daemons (always visible for Zrok)
            val isConfigurable = state.type == DaemonType.ZROK_TUNNEL && onConfigureClick != null
            ivConfigure.visibility = if (isConfigurable) View.VISIBLE else View.GONE
            if (isConfigurable) {
                ivConfigure.setOnClickListener {
                    onConfigureClick?.invoke(state.type)
                }
            }
            
            // Show download log button (only if callback provided — debug builds only)
            val hasLog = onDownloadLog != null && hasLogFile(state.type)
            ivDownloadLog.visibility = if (hasLog) View.VISIBLE else View.GONE
            if (hasLog) {
                ivDownloadLog.setOnClickListener {
                    onDownloadLog?.invoke(state.type)
                }
            }
            
            // Handle configuration click for daemons that need setup
            if (state.needsConfiguration && onConfigureClick != null) {
                itemView.setOnClickListener {
                    onConfigureClick.invoke(state.type)
                }
                mainRow.setOnClickListener {
                    onConfigureClick.invoke(state.type)
                }
                // Make status text look clickable
                tvDaemonStatus.text = "${state.configurationMessage ?: "Configuration required"} ⚙️"
            } else if (hasSubprocesses) {
                // Click to expand/collapse subprocesses
                mainRow.setOnClickListener {
                    expandedStates[state.type] = !(expandedStates[state.type] ?: false)
                    notifyItemChanged(bindingAdapterPosition)
                }
                itemView.setOnClickListener(null)
            } else {
                mainRow.setOnClickListener(null)
                itemView.setOnClickListener(null)
            }
            
            // Populate subprocess list
            if (hasSubprocesses) {
                subprocessList.removeAllViews()
                state.subprocesses.forEach { subprocess ->
                    val subView = LayoutInflater.from(itemView.context)
                        .inflate(android.R.layout.simple_list_item_2, subprocessList, false)
                    
                    subView.findViewById<TextView>(android.R.id.text1).apply {
                        text = "${subprocess.name} (PID: ${subprocess.pid})"
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        textSize = 12f
                    }
                    
                    subView.findViewById<TextView>(android.R.id.text2).apply {
                        text = "Uptime: ${subprocess.uptime}"
                        setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                        textSize = 10f
                    }
                    subprocessList.addView(subView)
                }
            }
        }
        
        private fun getDaemonDisplayName(type: DaemonType): String {
            return when (type) {
                DaemonType.CAMERA_DAEMON -> "📷 Camera Daemon"
                DaemonType.SENTRY_DAEMON -> "🛡️ Sentry Daemon"
                DaemonType.ACC_SENTRY_DAEMON -> "🚗 ACC Sentry"
                DaemonType.SINGBOX_PROXY -> "🔗 Sing-box Proxy"
                DaemonType.CLOUDFLARED_TUNNEL -> "☁️ Cloudflared Tunnel"
                DaemonType.ZROK_TUNNEL -> "🌐 Zrok Tunnel"
                DaemonType.TELEGRAM_DAEMON -> "📱 Telegram Bot"
            }
        }
        
        private fun hasLogFile(type: DaemonType): Boolean {
            return getLogFilePath(type) != null
        }
    }
    
    companion object {
        /**
         * Map daemon types to their log file paths.
         * Returns null for daemons without a known log file.
         */
        fun getLogFilePath(type: DaemonType): String? {
            return when (type) {
                DaemonType.CAMERA_DAEMON -> "/data/local/tmp/cam_daemon.log"
                DaemonType.SENTRY_DAEMON -> "/data/local/tmp/sentry_daemon.log"
                DaemonType.ACC_SENTRY_DAEMON -> "/data/local/tmp/acc_sentry_daemon.log"
                DaemonType.CLOUDFLARED_TUNNEL -> "/data/local/tmp/cloudflared.log"
                DaemonType.ZROK_TUNNEL -> "/data/local/tmp/zrok.log"
                DaemonType.SINGBOX_PROXY -> "/data/local/tmp/singbox.log"
                DaemonType.TELEGRAM_DAEMON -> "/data/local/tmp/telegrambotdaemon.log"
            }
        }
    }
    
    private class DaemonDiffCallback : DiffUtil.ItemCallback<DaemonState>() {
        override fun areItemsTheSame(oldItem: DaemonState, newItem: DaemonState): Boolean {
            return oldItem.type == newItem.type
        }
        
        override fun areContentsTheSame(oldItem: DaemonState, newItem: DaemonState): Boolean {
            return oldItem == newItem
        }
    }
}
