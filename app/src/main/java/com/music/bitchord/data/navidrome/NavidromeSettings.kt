package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.webdav.WebDavConfig
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

    /** Every Navidrome [com.music.bitchord.data.model.Song.videoId] starts with this, then the server's song id. */
    const val ID_PREFIX = com.music.bitchord.data.model.NAVIDROME_ID_PREFIX

    /** A Navidrome song, which YouTube has never heard of: nothing YouTube-side should be asked about it. */
    fun isNavidromeId(videoId: String?): Boolean = videoId?.startsWith(ID_PREFIX) == true

    private lateinit var prefs: SharedPreferences

    private val _url = MutableStateFlow("")
    private val _username = MutableStateFlow("")
    private val _password = MutableStateFlow("")

    val url: StateFlow<String> = _url.asStateFlow()
    val username: StateFlow<String> = _username.asStateFlow()
    val password: StateFlow<String> = _password.asStateFlow()

    /**
     * The Subsonic token salt, kept for as long as the login is. A fresh salt
     * per session would change every stream and cover URL on each start, and
     * the image and audio caches are keyed by URL.
     */
    var salt: String = ""
        private set

    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, "bitchord_navidrome", "bitchord_navidrome_plain")
        _url.value = prefs.getString(KEY_URL, "").orEmpty()
        _username.value = prefs.getString(KEY_USERNAME, "").orEmpty()
        _password.value = prefs.getString(KEY_PASSWORD, "").orEmpty()
        salt = prefs.getString(KEY_SALT, null) ?: newSalt().also { prefs.edit().putString(KEY_SALT, it).apply() }
        NavidromeClips.init(context)
        NavidromeSongInfo.init()
    }

    fun isConfigured(): Boolean = isConfigured(_url.value, _username.value)

    fun isConfigured(url: String, username: String): Boolean =
        normalizeUrl(url).isNotEmpty() && username.isNotBlank()

    fun save(url: String, username: String, password: String) {
        _url.value = normalizeUrl(url)
        _username.value = username.trim()
        _password.value = password
        salt = newSalt()
        prefs.edit()
            .putString(KEY_URL, _url.value)
            .putString(KEY_USERNAME, _username.value)
            .putString(KEY_PASSWORD, password)
            .putString(KEY_SALT, salt)
            .apply()
        NavidromeRepository.invalidate()
    }

    fun clear() = save("", "", "")

    /** `music.example.com/` → `https://music.example.com`; anything without a host reads as blank. */
    fun normalizeUrl(raw: String): String =
        WebDavConfig.normalizeUrl(raw).takeIf(WebDavConfig::isConfigured).orEmpty()

    fun newSalt(): String =
        ByteArray(8).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private const val KEY_URL = "url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SALT = "salt"
}
