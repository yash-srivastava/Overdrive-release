package com.overdrive.app.ui.view

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R

/** One castable app as the Projection picker shows it. */
internal class ProjectionAppTile(val label: String, val pkg: String, val icon: Drawable?)

/**
 * App tiles for the Projection picker: the idle grid ([compact] = false) and the strip under
 * the live mirror ([compact] = true). A tap casts the app; a long-press opens that app's
 * window and tools menu. The app currently on the cluster is outlined.
 */
internal class ProjectionAppTileAdapter(
    private val compact: Boolean,
    private val onTap: (String) -> Unit,
    private val onLongPress: (String) -> Unit,
) : RecyclerView.Adapter<ProjectionAppTileAdapter.Holder>() {

    private val items = ArrayList<ProjectionAppTile>()
    private var livePackage = ""

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.projectionTileIcon)
        val label: TextView? = view.findViewById(R.id.projectionTileLabel)
        val live: View? = view.findViewById(R.id.projectionTileLive)
        val badge: ImageView? = view.findViewById(R.id.projectionTileBadge)
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ProjectionAppTile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setLivePackage(pkg: String) {
        if (pkg == livePackage) return
        val old = livePackage
        livePackage = pkg
        indexOf(old).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        indexOf(pkg).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    private fun indexOf(pkg: String): Int =
        if (pkg.isEmpty()) -1 else items.indexOfFirst { it.pkg == pkg }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (compact) R.layout.item_projection_app_chip else R.layout.item_projection_app_tile
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val view = holder.itemView
        val res = view.resources
        val isLive = item.pkg == livePackage
        // A Drawable keeps one set of bounds, so every tile draws its own copy.
        val icon = item.icon?.constantState?.newDrawable(res)?.mutate() ?: item.icon
        if (icon != null) holder.icon.setImageDrawable(icon)
        else holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        holder.label?.text = item.label
        holder.live?.visibility = if (isLive) View.VISIBLE else View.GONE
        holder.badge?.visibility = if (isLive) View.GONE else View.VISIBLE
        // Shared Revamp tile; the app on the cluster gets a primary outline on a tonal fill.
        (view as? MaterialCardView)?.let { card ->
            card.strokeWidth = if (isLive) res.getDimensionPixelSize(R.dimen.od_space_2) else 0
            card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                if (isLive) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        view.contentDescription =
            if (isLive) res.getString(R.string.projection_app_live_description, item.label) else item.label
        if (compact) view.tooltipText = item.label
        view.setOnClickListener { onTap(item.pkg) }
        view.setOnLongClickListener { onLongPress(item.pkg); true }
        // Name both gestures for screen readers: tap casts, long-press opens the app's menu.
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_CLICK, res.getString(R.string.projection_cast)))
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, res.getString(R.string.projection_app_actions)))
            }
        })
    }
}
