package com.music.bitchord.data.navidrome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Navidrome server this fork plays from (fork-only).
 *
 * Kept apart from `AppSettings` so upstream
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

    private lateinit var prefs: NavidromePlatform.Store

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

    fun init(store: NavidromePlatform.Store) {
        prefs = store
        _url.value = prefs.string(KEY_URL).orEmpty()
        _username.value = prefs.string(KEY_USERNAME).orEmpty()
        _password.value = prefs.string(KEY_PASSWORD).orEmpty()
        salt = prefs.string(KEY_SALT) ?: newSalt().also { prefs.putStrings(mapOf(KEY_SALT to it)) }
    }

    /** The library's own album and artist pages (`LocalGroups` on the phone builds the same ids). */
    fun albumPageId(name: String): String = "local:group:album:navidrome:$name"

    fun artistPageId(name: String): String = "local:group:artist:navidrome:$name"

    fun isConfigured(): Boolean = isConfigured(_url.value, _username.value)

    fun isConfigured(url: String, username: String): Boolean =
        normalizeUrl(url).isNotEmpty() && username.isNotBlank()

    /** Bumped once per [save], after every value is in place: what screens reload on (one reload, never mixed credentials). */
    private val _saved = MutableStateFlow(0)
    val saved: StateFlow<Int> = _saved.asStateFlow()

    fun save(url: String, username: String, password: String) {
        _url.value = normalizeUrl(url)
        _username.value = username.trim()
        _password.value = password
        salt = newSalt()
        prefs.putStrings(mapOf(KEY_URL to _url.value, KEY_USERNAME to _username.value, KEY_PASSWORD to password, KEY_SALT to salt))
        NavidromeRepository.invalidate()
        _saved.value++
    }

    fun clear() = save("", "", "")

    /** `music.example.com/` → `https://music.example.com`; anything without a host reads as blank. */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val url = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) trimmed else "https://$trimmed"
        // "not a url" normalizes to "https://not a url": only a parseable host counts.
        return if (runCatching { java.net.URI(url).host }.getOrNull().isNullOrBlank()) "" else url
    }

    fun newSalt(): String =
        ByteArray(8).also(java.security.SecureRandom()::nextBytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    private const val KEY_URL = "url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SALT = "salt"
}
