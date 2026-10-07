package com.terminuke.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [HostEntity::class, SshKeyEntity::class, KnownHostEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun keyDao(): KeyDao
    abstract fun knownHostDao(): KnownHostDao
}
