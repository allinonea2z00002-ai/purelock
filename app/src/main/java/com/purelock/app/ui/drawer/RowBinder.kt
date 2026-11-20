package com.purelock.app.ui.drawer

import android.content.Context
import android.content.res.ColorStateList
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.purelock.app.databinding.ItemSfSettingRowBinding

/**
 * Binds the shared settings row layout (`item_sf_setting_row.xml`): tinted icon
 * circle + bold title + grey description (+ optional blue extra line) + a chevron
 * for navigation rows, or a green switch for toggle rows.
 */
object RowBinder {

    fun navRow(
        row: ItemSfSettingRowBinding,
        context: Context,
        @DrawableRes icon: Int,
        @ColorRes tintBg: Int,
        @ColorRes tintIcon: Int,
        @StringRes title: Int,
        @StringRes desc: Int = 0,
        @StringRes extra: Int = 0,
        onClick: () -> Unit
    ) {
        styleRow(row, context, icon, tintBg, tintIcon, title, desc, extra)
        row.rowChevron.isVisible = true
        row.rowSwitch.isVisible = false
        row.root.setOnClickListener { onClick() }
    }

    fun toggleRow(
        row: ItemSfSettingRowBinding,
        context: Context,
        @DrawableRes icon: Int,
        @ColorRes tintBg: Int,
        @ColorRes tintIcon: Int,
        @StringRes title: Int,
        @StringRes desc: Int = 0,
        initial: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        styleRow(row, context, icon, tintBg, tintIcon, title, desc)
        row.rowChevron.isVisible = false
        row.rowSwitch.isVisible = true
        // setChecked only fires the listener when the value actually changes, so
        // re-rendering the stored value never loops back into a save.
        row.rowSwitch.isChecked = initial
        row.rowSwitch.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        row.root.setOnClickListener { row.rowSwitch.toggle() }
    }

    private fun styleRow(
        row: ItemSfSettingRowBinding,
        context: Context,
        @DrawableRes icon: Int,
        @ColorRes tintBg: Int,
        @ColorRes tintIcon: Int,
        @StringRes title: Int,
        @StringRes desc: Int,
        @StringRes extra: Int = 0
    ) {
        row.rowIcon.setImageResource(icon)
        ImageViewCompat.setImageTintList(
            row.rowIcon,
            ColorStateList.valueOf(ContextCompat.getColor(context, tintIcon))
        )
        row.rowIconBg.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, tintBg))
        row.rowTitle.setText(title)
        if (desc != 0) {
            row.rowDesc.setText(desc)
            row.rowDesc.isVisible = true
        } else {
            row.rowDesc.isVisible = false
        }
        if (extra != 0) {
            row.rowExtra.setText(extra)
            row.rowExtra.isVisible = true
        } else {
            row.rowExtra.isVisible = false
        }
    }
}
