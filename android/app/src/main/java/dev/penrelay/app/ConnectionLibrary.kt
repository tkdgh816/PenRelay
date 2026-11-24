package dev.penrelay.app

import android.content.SharedPreferences
import dev.penrelay.session.ConnectionPath
import org.json.JSONArray
import org.json.JSONObject

internal class ConnectionLibrary(private val prefs: SharedPreferences) {
    private val historyKey = "recent-pc-addresses"
    private val profilePrefix = "connection-profile:"

    fun recent(): List<String> {
        val saved = prefs.getString(historyKey, null)
        if (saved == null) {
            val previous = listOf(ConnectionPath.Wifi, ConnectionPath.Tether).mapNotNull {
                prefs.getString(ConnectionSettings.addressKey(it), null)?.trim()?.takeIf(String::isNotEmpty)
            }.distinct()
            writeHistory(previous)
            return previous
        }
        val entries = JSONArray(saved)
        return List(entries.length()) { entries.getString(it) }
    }

    fun remember(settings: ConnectionSettings) {
        if (settings.path != ConnectionPath.Wifi && settings.path != ConnectionPath.Tether) return
        val addresses = recent()
        writeHistory((listOf(settings.host) + addresses.filterNot { it.equals(settings.host, true) }).take(10))
    }

    fun removeAddress(address: String) = writeHistory(recent() - address)

    private fun writeHistory(entries: List<String>) {
        prefs.edit().putString(historyKey, JSONArray(entries).toString()).apply()
    }

    fun names() = prefs.all.keys.filter { it.startsWith(profilePrefix) }
        .map { it.removePrefix(profilePrefix) }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun delete(name: String) = prefs.edit().remove(profilePrefix + name).apply()

    fun save(name: String, settings: ConnectionSettings) {
        val record = JSONObject().put("route", settings.path.name).put("host", settings.host)
            .put("port", settings.port).put("pairingPort", settings.pairingPort)
        prefs.edit().putString(profilePrefix + name, record.toString()).apply()
    }

    fun load(name: String): ConnectionSettings {
        val record = JSONObject(requireNotNull(prefs.getString(profilePrefix + name, null)))
        return ConnectionSettings(ConnectionPath.valueOf(record.getString("route")), record.getString("host"),
            record.getInt("port"), record.getInt("pairingPort"))
    }
}
