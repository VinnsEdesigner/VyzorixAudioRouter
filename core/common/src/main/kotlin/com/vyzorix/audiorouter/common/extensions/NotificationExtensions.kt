package com.vyzorix.audiorouter.common.extensions

import android.app.Notification
import android.content.Context
import android.text.Html
import android.text.Spanned
import android.text.SpannedString

/**
 * Notification helpers used by the dashboard notification (Layer 5).
 *
 * The helpers here intentionally do NOT depend on `RemoteViews` so the
 * module stays unit-testable under Robolectric (which doesn't ship a
 * working `RemoteViews` implementation). The notification layer is
 * responsible for the actual `RemoteViews` construction.
 */

/**
 * Returns the user-visible "expanded" text of a notification, joining the
 * `EXTRA_TITLE` + `EXTRA_TEXT` extras with a separator. Used by the
 * crash bundle assembler to capture the dashboard state at crash time.
 */
public fun Notification.extractVisibleText(separator: String = " — "): String {
    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    return when {
        !title.isNullOrBlank() && !text.isNullOrBlank() -> "$title$separator$text"
        !title.isNullOrBlank() -> title
        !text.isNullOrBlank() -> text
        else -> ""
    }
}

/**
 * Renders an HTML-formatted string into a [Spanned] using the
 * platform's safest parser flags. Used by the dashboard notification's
 * "status line" which mixes bold + color spans for the route state.
 *
 * Empty / null input collapses to an empty span so the caller can pass
 * the result straight into `setContentText` without a null check.
 */
public fun String?.applyTextStyle(): Spanned {
    if (this.isNullOrBlank()) return SpannedString("")
    return Html.fromHtml(this, Html.FROM_HTML_MODE_COMPACT)
}

/**
 * Returns the package's user-visible application name. The dashboard
 * notification uses this for the foreground-service `extras` `EXTRA_SUB_TEXT`
 * so different builds (debug vs release) are distinguishable in the shade.
 */
public fun Context.applicationLabel(): String {
    val info = applicationInfo
    val labelRes = info.labelRes
    return if (labelRes != 0) {
        getString(labelRes)
    } else {
        info.nonLocalizedLabel?.toString() ?: packageName
    }
}
