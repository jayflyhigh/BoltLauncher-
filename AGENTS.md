# Bolt Launcher

For changes to visible launcher interactions, follow the motion guidance in
[CONTRIBUTING.md](CONTRIBUTING.md#launcher-motion). Use the shared motion helpers where
animation helps explain the state change, and check that the interaction also works with
animations disabled.

## Search caret requirements

- The focused search input must always show a continuously blinking typing caret in
  regular search and Thunder mode. Never make the caret permanently steady for a
  particular theme or mode.
- Keep caret rendering centralized in `BoltSearchInput`. On Android 10 and newer,
  use the platform's blinking text caret with a theme-tinted cursor drawable. On
  older Android versions, use the view's invalidation-driven blinking fallback.
- Every theme overlay must define both `boltSearchCursorColor` and
  `boltThunderCursorColor`. Choose colors that fit that theme and remain clearly
  visible against the corresponding dock. The shared input must also apply a
  contrast-safe fallback during dock transitions and wallpaper palette changes.
- When adding a theme, verify the caret in regular search and Thunder mode, while
  typing, after changing the query/cursor position, and immediately after switching
  themes. Check both light and dark surfaces. A missing, invisible, or stagnant
  caret is a theme bug and must be fixed before the theme is considered complete.
