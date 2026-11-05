package com.music.bitchord.desktop

import javafx.geometry.Insets
import javafx.scene.control.Label

/**
 * Fork: the line of help at the top of the YouTube Music sign-in window, from Desktop Revived
 * 2bed5513.
 *
 * Upstream greyed "Use this profile" on each page load unless the session cookie was already in
 * the jar, and only re-checked on the next page load. The cookie can land after WebKit reports the
 * page done, so the button stayed grey on music.youtube.com with the listener signed in and nothing
 * left to load; pressing it did nothing either way. The window now enables the button on any
 * music.youtube.com page and checks for the cookie when it is pressed, and this label says what to
 * do when it isn't there yet. Google also turns embedded browsers away on some accounts ("This
 * browser or app may not be secure"); the label names our way round that, pasting the cookie, which
 * the account dialog offers ("Paste a cookie instead").
 */
internal object ForkWebSignInGuidance {

    fun label(): Label = Label(
        DesktopStrings[
            "fork_web_sign_in_hint",
            "If Google says this browser is not secure, close this window and use \"Paste a cookie instead\".",
        ],
    ).apply {
        style = "-fx-text-fill: #aaaaaa; -fx-font-size: 11px;"
        isWrapText = true
        maxWidth = 460.0
        padding = Insets(8.0)
    }

    /** "Use this profile" was pressed before YouTube Music had set its sign-in cookie. */
    fun notReady(label: Label) {
        label.text = DesktopStrings[
            "fork_web_sign_in_not_ready",
            "YouTube Music has not finished signing in yet. Finish the Google sign-in and pick your " +
                "profile, then press \"Use this profile\" again. If Google blocks this window, close it " +
                "and use \"Paste a cookie instead\".",
        ]
    }
}
