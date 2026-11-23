package app.olauncher.ui

import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton

internal fun AppCompatImageButton.setXpIcon(@DrawableRes icon: Int) {
    imageTintList = null
    setImageResource(icon)
}

internal fun AppCompatButton.setXpIcon(@DrawableRes icon: Int) {
    // The menu row can carry a tint from either TextView or AppCompat.
    compoundDrawableTintList = null
    supportCompoundDrawablesTintList = null
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
}
