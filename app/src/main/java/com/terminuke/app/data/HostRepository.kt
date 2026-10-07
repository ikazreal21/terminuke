package com.terminuke.app.data

import com.terminuke.app.data.db.HostDao
import com.terminuke.app.data.db.HostEntity
import com.terminuke.app.domain.HostInput
import com.terminuke.app.domain.HostValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class HostRepository(private val dao: HostDao) {
    fun observe(query: String): Flow<List<HostEntity>> = dao.observeHosts(query)
    suspend fun get(id: Long) = dao.get(id)
    suspend fun save(input: HostInput): Long {
        val errors = HostValidator.validate(input)
        require(errors.isEmpty()) { errors.values.first() }
        return dao.upsert(
            HostEntity(
                id = input.id,
                label = input.label.trim(), hostname = input.hostname.trim(),
                port = input.port.toInt(), username = input.username.trim(),
                authType = input.authType,
                keyAlias = input.keyAlias,
            ),
        )
    }
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun touchConnected(id: Long) = dao.touchConnected(id, System.currentTimeMillis())

    suspend fun exportJson(): String {
        val hosts = dao.observeHosts("").first()
        val jsonHosts = org.json.JSONArray()
        hosts.forEach { host ->
            jsonHosts.put(
                org.json.JSONObject()
                    .put("label", host.label)
                    .put("hostname", host.hostname)
                    .put("port", host.port)
                    .put("username", host.username)
                    .put("authType", if (host.authType == "key") "password" else host.authType)
                    .put("favorite", host.isFavorite),
            )
        }
        return org.json.JSONObject().put("version", 1).put("hosts", jsonHosts).toString(2)
    }

    suspend fun importJson(json: String) {
        val document = org.json.JSONObject(json)
        require(document.optInt("version") == 1) { "Unsupported hosts backup version." }
        val hosts = document.getJSONArray("hosts")
        require(hosts.length() <= 1_000) { "Backup contains too many hosts." }
        for (index in 0 until hosts.length()) {
            val host = hosts.getJSONObject(index)
            save(
                HostInput(
                    label = host.getString("label"),
                    hostname = host.getString("hostname"),
                    port = host.getInt("port").toString(),
                    username = host.getString("username"),
                    authType = host.optString("authType", "password"),
                ),
            )
        }
    }
}
