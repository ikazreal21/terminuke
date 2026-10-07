package com.terminuke.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

data class AppSettings(val fontSizeSp: Int = 14, val keepScreenOn: Boolean = true, val secureFlag: Boolean = false)

class SettingsRepository(private val context: Context) {
    val settings = context.settingsStore.data.map { values ->
        AppSettings(
            fontSizeSp = values[FONT_SIZE]?.coerceIn(12, 24) ?: 14,
            keepScreenOn = values[KEEP_SCREEN_ON] ?: true,
            secureFlag = values[SECURE_FLAG] ?: false,
        )
    }

    suspend fun setFontSize(value: Int) = context.settingsStore.edit { it[FONT_SIZE] = value.coerceIn(12, 24) }
    suspend fun setKeepScreenOn(value: Boolean) = context.settingsStore.edit { it[KEEP_SCREEN_ON] = value }
    suspend fun setSecureFlag(value: Boolean) = context.settingsStore.edit { it[SECURE_FLAG] = value }

    private companion object {
        val FONT_SIZE = intPreferencesKey("font_size")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SECURE_FLAG = booleanPreferencesKey("secure_flag")
    }
}
