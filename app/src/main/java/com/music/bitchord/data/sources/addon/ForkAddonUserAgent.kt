package com.music.bitchord.data.sources.addon

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.ForkAddonUserAgents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fork: the phone's per-addon User-Agent choice ([ForkAddonUserAgents] says why), kept in its own
 * preferences by addon host. `AddonClient.body` and `SourceFormats.fetch` send [agent]; adding an
 * addon goes through [ForkAddonUserAgents.retryingRefused]; the Sources row shows [ForkAddonUserAgentLine]
 * and `MainActivity` lists the presets.
 */
object ForkAddonUserAgent : ForkAddonUserAgents.Store {

    private const val PREFS = "fork_addon_user_agent"

    private var prefs: SharedPreferences? = null
    private val _picks = MutableStateFlow<Map<String, String>>(emptyMap())
    val picks: StateFlow<Map<String, String>> = _picks.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        ForkAddonLimitsPhone.init(context) // the addon limits keep their own preferences
        _picks.value = p.all.mapNotNull { (host, id) -> (id as? String)?.let { host to it } }.toMap()
    }

    override fun picked(host: String): String? = _picks.value[host]

    override fun pick(host: String, presetId: String?) {
        _picks.update { if (presetId == null) it - host else it + (host to presetId) }
        prefs?.edit()?.apply { if (presetId == null) remove(host) else putString(host, presetId) }?.apply()
    }

    /** The agent for a request to [url], or [appAgent] for an addon left on the default. */
    fun agent(url: String, appAgent: String): String =
        ForkAddonUserAgents.agentFor(ForkAddonUserAgents.hostOf(url)?.let(::picked), appAgent)

    /** The preset in force for the addon at [baseUrl]. */
    fun presetFor(baseUrl: String): ForkAddonUserAgents.Preset =
        ForkAddonUserAgents.preset(ForkAddonUserAgents.hostOf(baseUrl)?.let(::picked))

    fun choose(baseUrl: String, presetId: String) {
        val host = ForkAddonUserAgents.hostOf(baseUrl) ?: return
        pick(host, presetId.takeUnless { it == ForkAddonUserAgents.BITCHORD })
    }
}
