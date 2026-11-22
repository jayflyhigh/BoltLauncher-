package app.olauncher.ui

import android.view.View
import androidx.core.view.doOnLayout
import app.olauncher.databinding.AdapterAppDrawerBinding

internal fun AdapterAppDrawerBinding.updateTypoHint(show: Boolean) {
    typoHint.tag = show
    if (!show) {
        typoHint.visibility = View.INVISIBLE
        return
    }
    root.doOnLayout {
        val available = appTitle.width - appTitle.paddingLeft - appTitle.paddingRight
        val gap = 8f * root.resources.displayMetrics.density
        val fits = appTitle.paint.measureText(appTitle.text.toString()) +
            typoHint.paint.measureText(typoHint.text.toString()) + gap <= available
        typoHint.visibility = if (typoHint.tag == true && appTitle.visibility == View.VISIBLE && fits)
            View.VISIBLE else View.INVISIBLE
    }
}
