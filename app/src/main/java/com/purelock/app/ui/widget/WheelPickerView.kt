package com.purelock.app.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.purelock.app.R
import kotlin.math.abs

/**
 * Single-column vertical wheel picker (SPEC §1.4): rows scroll vertically, the centred row
 * sits on a light highlight band in bold dark text, rows above/below fade to grey.
 * Used three-across for Take a Break (day/hr/min, Duration/Break/Sessions) and two-across
 * for the schedule "Once I Have Spent" picker.
 */
class WheelPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val itemHeightPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 52f, resources.displayMetrics).toInt()

    private val recycler: RecyclerView
    private var items: List<String> = emptyList()
    private var selected = 0

    /** Fired when the centred row changes (user scroll or [setSelected]). */
    var onSelectionChanged: ((Int) -> Unit)? = null

    val selectedIndex: Int get() = selected
    val selectedItem: String? get() = items.getOrNull(selected)

    init {
        // Highlight band behind the centre row.
        val band = View(context).apply {
            setBackgroundResource(R.drawable.bg_sf_wheel_band)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, itemHeightPx).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
        }
        addView(band)
        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(recycler)
        LinearSnapHelper().attachToRecyclerView(recycler)
        recycler.adapter = Adapter()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = refreshStyles()
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) snapToCentre()
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = ((h - itemHeightPx) / 2).coerceAtLeast(0)
        if (recycler.paddingTop != pad) {
            recycler.setPadding(0, pad, 0, pad)
            recycler.clipToPadding = false
            scrollToSelected(animate = false)
        }
    }

    fun setItems(newItems: List<String>, selectedIndex: Int = 0) {
        items = newItems
        selected = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        recycler.adapter?.notifyDataSetChanged()
        recycler.post { scrollToSelected(animate = false) }
    }

    fun setSelected(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val changed = target != selected
        selected = target
        scrollToSelected(animate)
        refreshStyles()
        if (changed) onSelectionChanged?.invoke(selected)
    }

    private fun scrollToSelected(animate: Boolean) {
        if (items.isEmpty()) return
        if (animate) recycler.smoothScrollToPosition(selected)
        else (recycler.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(selected, centreOffset())
    }

    private fun centreOffset(): Int = (height - itemHeightPx) / 2

    private fun snapToCentre() {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val centreY = height / 2
        var best = first
        var bestDist = Int.MAX_VALUE
        for (i in first..last) {
            val child = lm.findViewByPosition(i) ?: continue
            val dist = abs((child.top + child.bottom) / 2 - centreY)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        if (best != selected) {
            selected = best
            onSelectionChanged?.invoke(selected)
        }
        refreshStyles()
    }

    private fun refreshStyles() {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val centreY = height / 2
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            val tv = child as? TextView ?: continue
            val dist = abs((child.top + child.bottom) / 2 - centreY)
            val isCentre = dist < itemHeightPx / 2
            tv.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isCentre) R.color.sf_text else R.color.sf_muted2
                )
            )
            tv.alpha = if (isCentre) 1f else (1f - (dist.toFloat() / height).coerceIn(0f, 0.7f))
            tv.setTypeface(null, if (isCentre) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wheel_row, parent, false)
            return RowVH(v as TextView)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            holder.tv.text = items[position]
            holder.tv.setOnClickListener { setSelected(position) }
        }
    }

    private class RowVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
