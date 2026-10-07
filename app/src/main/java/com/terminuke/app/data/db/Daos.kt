package com.terminuke.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts WHERE label LIKE '%' || :query || '%' OR hostname LIKE '%' || :query || '%' ORDER BY isFavorite DESC, lastConnectedAt DESC, label COLLATE NOCASE")
    fun observeHosts(query: String): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): HostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: HostEntity): Long

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE hosts SET lastConnectedAt = :time WHERE id = :id")
    suspend fun touchConnected(id: Long, time: Long)
}

@Dao
interface KeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name COLLATE NOCASE")
    fun observeKeys(): Flow<List<SshKeyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: SshKeyEntity)

    @Query("SELECT * FROM ssh_keys WHERE alias = :alias")
    suspend fun get(alias: String): SshKeyEntity?

    @Query("DELETE FROM ssh_keys WHERE alias = :alias")
    suspend fun delete(alias: String)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port")
    suspend fun get(hostname: String, port: Int): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun trust(host: KnownHostEntity)
}
