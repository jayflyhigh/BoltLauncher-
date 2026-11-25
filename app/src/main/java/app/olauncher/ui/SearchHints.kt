package app.olauncher.ui

import app.olauncher.R
import app.olauncher.data.Prefs

internal data class SearchHints(
    val home: Int,
    val regular: Int,
    val destination: Int,
    val thunder: Int,
    val rename: Int,
) {
    companion object {
        fun forTheme(theme: Prefs.LauncherTheme): SearchHints = when (theme) {
            Prefs.LauncherTheme.DEFAULT, Prefs.LauncherTheme.WALLPAPER -> SearchHints(
                R.string.bolt_hint_default_home,
                R.string.bolt_hint_default_regular,
                R.string.bolt_hint_default_destination,
                R.string.bolt_hint_default_thunder,
                R.string.bolt_hint_default_rename,
            )
            Prefs.LauncherTheme.FROSTED_GLASS -> SearchHints(
                R.string.bolt_hint_frost_home,
                R.string.bolt_hint_frost_regular,
                R.string.bolt_hint_frost_destination,
                R.string.bolt_hint_frost_thunder,
                R.string.bolt_hint_frost_rename,
            )
            Prefs.LauncherTheme.LIQUID_GLASS -> SearchHints(
                R.string.bolt_hint_liquid_home,
                R.string.bolt_hint_liquid_regular,
                R.string.bolt_hint_liquid_destination,
                R.string.bolt_hint_liquid_thunder,
                R.string.bolt_hint_liquid_rename,
            )
            Prefs.LauncherTheme.E_INK -> SearchHints(
                R.string.bolt_hint_eink_home,
                R.string.bolt_hint_eink_regular,
                R.string.bolt_hint_eink_destination,
                R.string.bolt_hint_eink_thunder,
                R.string.bolt_hint_eink_rename,
            )
            Prefs.LauncherTheme.PAPER_NOTEBOOK -> SearchHints(
                R.string.bolt_hint_notebook_home,
                R.string.bolt_hint_notebook_regular,
                R.string.bolt_hint_notebook_destination,
                R.string.bolt_hint_notebook_thunder,
                R.string.bolt_hint_notebook_rename,
            )
            Prefs.LauncherTheme.SMOKED_GLASS -> SearchHints(
                R.string.bolt_hint_smoke_home,
                R.string.bolt_hint_smoke_regular,
                R.string.bolt_hint_smoke_destination,
                R.string.bolt_hint_smoke_thunder,
                R.string.bolt_hint_smoke_rename,
            )
            Prefs.LauncherTheme.WINDOWS_XP -> SearchHints(
                R.string.bolt_hint_xp_home,
                R.string.bolt_hint_xp_regular,
                R.string.bolt_hint_xp_destination,
                R.string.bolt_hint_xp_thunder,
                R.string.bolt_hint_xp_rename,
            )
            Prefs.LauncherTheme.WII_U -> SearchHints(
                R.string.bolt_hint_wii_home,
                R.string.bolt_hint_wii_regular,
                R.string.bolt_hint_wii_destination,
                R.string.bolt_hint_wii_thunder,
                R.string.bolt_hint_wii_rename,
            )
            Prefs.LauncherTheme.SEATTLE_SEAHAWKS -> SearchHints(
                R.string.bolt_hint_hawks_home,
                R.string.bolt_hint_hawks_regular,
                R.string.bolt_hint_hawks_destination,
                R.string.bolt_hint_hawks_thunder,
                R.string.bolt_hint_hawks_rename,
            )
            Prefs.LauncherTheme.COZY_QUEST -> SearchHints(
                R.string.bolt_cozy_home_hint,
                R.string.bolt_cozy_search_hint,
                R.string.bolt_cozy_destination_hint,
                R.string.bolt_cozy_thunder_hint,
                R.string.bolt_cozy_rename_hint,
            )
        }
    }
}
