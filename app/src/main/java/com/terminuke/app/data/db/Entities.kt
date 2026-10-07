package com.terminuke.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: String = "password",
    val keyAlias: String? = null,
    val lastConnectedAt: Long? = null,
    val isFavorite: Boolean = false,
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey val alias: String,
    val name: String,
    val publicOpenSsh: String,
    val keyType: String = "ed25519",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "known_hosts", primaryKeys = ["hostname", "port"])
data class KnownHostEntity(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val trustedAt: Long = System.currentTimeMillis(),
)
