package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Navidrome server this fork plays from (fork-only).
 *
 * Kept apart from [com.music.bitchord.data.settings.AppSettings] so upstream
 * changes to that file never conflict with it. All three values live in their
 * own encrypted store: the password is needed again later for Navidrome's own
 * login (animated backdrops sit behind it), so it can't be reduced to a token.
 * Both files are excluded from backups, like `bitchord_auth`.
 */
object NavidromeSettings {

    const val BROWSE_ID = "local:navidrome"

    private lateinit var prefs: SharedPreferences

    private val _url = MutableStateFlow("")
    private val _username = MutableStateFlow("")
    private val _password = MutableStateFlow("")

    val url: StateFlow<String> = _url.asStateFlow()
    val username: StateFlow<String> = _username.asStateFlow()
    val password: StateFlow<String> = _password.asStateFlow()

    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, "bitchord_navidrome", "bitchord_navidrome_plain")
        _url.value = prefs.getString(KEY_URL, "").orEmpty()
        _username.value = prefs.getString(KEY_USERNAME, "").orEmpty()
        _password.value = prefs.getString(KEY_PASSWORD, "").orEmpty()
    }

    fun isConfigured(): Boolean = isConfigured(_url.value, _username.value)

    fun isConfigured(url: String, username: String): Boolean =
        normalizeUrl(url).isNotEmpty() && username.isNotBlank()

    fun save(url: String, username: String, password: String) {
        _url.value = normalizeUrl(url)
        _username.value = username.trim()
        _password.value = password
        prefs.edit()
            .putString(KEY_URL, _url.value)
            .putString(KEY_USERNAME, _username.value)
            .putString(KEY_PASSWORD, password)
            .apply()
        NavidromeRepository.invalidate()
    }

    fun clear() = save("", "", "")

    /** `music.example.com/` → `https://music.example.com`; blank stays blank. */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "https://$trimmed"
        return withScheme.takeIf { runCatching { java.net.URI(it).host }.getOrNull() != null }.orEmpty()
    }

    private const val KEY_URL = "url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
}
