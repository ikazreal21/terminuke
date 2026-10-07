package com.terminuke.app

import android.app.Application
import androidx.room.Room
import com.terminuke.app.crypto.CryptoManager
import com.terminuke.app.data.HostRepository
import com.terminuke.app.data.KeyRepository
import com.terminuke.app.data.SettingsRepository
import com.terminuke.app.data.db.AppDatabase
import com.terminuke.app.ssh.SshSessionManager

class TerminukeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val database: AppDatabase = Room.databaseBuilder(application, AppDatabase::class.java, "terminuke.db").build()
    val hosts = HostRepository(database.hostDao())
    val crypto = CryptoManager(application)
    val keys = KeyRepository(database.keyDao(), crypto)
    val settings = SettingsRepository(application)
    val sessions = SshSessionManager(database.hostDao(), database.knownHostDao())
}
