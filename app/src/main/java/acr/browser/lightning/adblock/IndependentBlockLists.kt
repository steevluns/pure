package acr.browser.lightning.adblock

import android.app.Application
import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

enum class IndependentListFormat { HOSTS, ABP, URL_LIST }

data class IndependentBlockList(
    val id: String,
    val title: String,
    val url: String,
    val format: IndependentListFormat,
    val fallbackUrl: String? = null,
)

@Singleton
class IndependentBlockLists @Inject constructor(
    private val application: Application,
) {
    val lists = listOf(
        IndependentBlockList("stevenblack", "StevenBlack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", IndependentListFormat.HOSTS),
        IndependentBlockList("oisd", "OISD", "https://big.oisd.nl/", IndependentListFormat.ABP, "https://small.oisd.nl/"),
        IndependentBlockList("1hosts", "1Hosts", "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/hosts.txt", IndependentListFormat.HOSTS, "https://cdn.jsdelivr.net/gh/badmojr/1Hosts@master/Lite/hosts.txt"),
        IndependentBlockList("porn", "Porn", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts", IndependentListFormat.HOSTS),
        IndependentBlockList("openphish", "OpenPhish (تصيّد)", "https://openphish.com/feed.txt", IndependentListFormat.URL_LIST),
        IndependentBlockList("popup_hosts", "Popup Hosts", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/adguard_popup_filter.txt", IndependentListFormat.ABP),
        IndependentBlockList("popup_ads", "Pop-Up Ads", "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/popupads.txt", IndependentListFormat.ABP),
        IndependentBlockList("adguard", "AdGuard", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", IndependentListFormat.ABP),
        IndependentBlockList("hagezi", "HaGeZi's", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt", IndependentListFormat.ABP),
        IndependentBlockList("youtube_ads_extra", "YouTube AdBlock 2", "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/BaseFilter/sections/adservers.txt", IndependentListFormat.ABP),
    )

    private val preferences = application.getSharedPreferences("independent_adblock_lists", Context.MODE_PRIVATE)
    private val executor = Executors.newFixedThreadPool(3)
    private val cache = HashMap<String, Set<String>>()

    fun isEnabled(id: String): Boolean = preferences.getBoolean("enabled_$id", true)
    fun setEnabled(id: String, enabled: Boolean) {
        preferences.edit().putBoolean("enabled_$id", enabled).apply()
    }

    @Synchronized private fun rules(id: String): Set<String> {
        cache[id]?.let { return it }
        val loaded = preferences.getStringSet("rules_$id", emptySet())?.toSet() ?: emptySet()
        cache[id] = loaded
        return loaded
    }

    fun ruleCount(id: String): Int = rules(id).size
    fun lastUpdateText(id: String): String {
        val time = preferences.getLong("updated_$id", 0L)
        if (time == 0L) return "لم يتم التحديث بعد"
        val minutes = ((System.currentTimeMillis() - time) / 60000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "آخر تحديث: الآن"
            minutes < 60 -> "آخر تحديث: قبل $minutes دقيقة"
            else -> "آخر تحديث: قبل ${minutes / 60} ساعة"
        }
    }

    fun update(id: String, callback: (Boolean, Int, String) -> Unit) {
        val definition = lists.firstOrNull { it.id == id } ?: return
        executor.execute {
            var result = downloadAndParse(definition.url, definition.format)
            if (result.isNullOrEmpty() && definition.fallbackUrl != null) {
                result = downloadAndParse(definition.fallbackUrl, definition.format)
            }
            if (!result.isNullOrEmpty()) {
                synchronized(this) { cache[id] = result }
                preferences.edit()
                    .putStringSet("rules_$id", HashSet(result))
                    .putLong("updated_$id", System.currentTimeMillis())
                    .apply()
                callback(true, result.size, "")
            } else {
                callback(false, ruleCount(id), "تعذّر تنزيل القائمة أو تحليلها")
            }
        }
    }

    /**
     * Checks the independently enabled filter subscriptions. These lists have their own
     * switches in settings and therefore must not be silently disabled by the legacy
     * built-in ad-block toggle.
     */
    fun isBlocked(uri: Uri): Boolean {
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        val url = uri.toString().lowercase()
        for (definition in lists) {
            if (!isEnabled(definition.id)) continue
            val entries = rules(definition.id)
            if (definition.format == IndependentListFormat.URL_LIST) {
                if (entries.any { url == it || url.startsWith("$it?") || url.startsWith("$it#") }) return true
            } else if (isHostMatch(host, entries)) {
                return true
            }
        }
        return false
    }

    private fun isHostMatch(host: String, entries: Set<String>): Boolean {
        var candidate = host.removePrefix("www.")
        if (entries.contains(candidate)) return true
        var dot = candidate.indexOf('.')
        while (dot >= 0) {
            if (entries.contains(candidate.substring(dot + 1))) return true
            dot = candidate.indexOf('.', dot + 1)
        }
        return false
    }

    private fun downloadAndParse(address: String, format: IndependentListFormat): Set<String>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(text, format).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(text: String, format: IndependentListFormat): Set<String> {
        val result = HashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return@forEach
            when (format) {
                IndependentListFormat.HOSTS -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                        parts.drop(1).map { it.lowercase().trimEnd('.') }
                            .filter { it.contains(".") && it != "localhost" }.forEach(result::add)
                    }
                }
                IndependentListFormat.ABP -> {
                    if (line.startsWith("@@") || line.contains("##") || line.contains("#@#")) return@forEach
                    if (line.startsWith("||")) {
                        var domain = line.removePrefix("||")
                        val end = domain.indexOfFirst { it == '^' || it == '/' || it == '$' || it == '*' }
                        if (end >= 0) domain = domain.substring(0, end)
                        domain = domain.lowercase().trimEnd('.')
                        if (domain.contains(".") && !domain.contains(" ")) result.add(domain)
                    }
                }
                IndependentListFormat.URL_LIST -> {
                    try {
                        val parsed = URI(line)
                        if ((parsed.scheme == "http" || parsed.scheme == "https") && !parsed.host.isNullOrBlank()) {
                            result.add(parsed.normalize().toString().trimEnd('/').lowercase())
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        return result
    }
}
