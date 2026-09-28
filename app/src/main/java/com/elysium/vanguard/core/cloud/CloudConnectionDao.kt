package com.elysium.vanguard.core.cloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.elysium.vanguard.core.database.CloudConnectionEntity

@Dao
interface CloudConnectionDao {
    @Query("SELECT * FROM cloud_connections")
    fun getAll(): List<CloudConnectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: CloudConnectionEntity)

    @Query("DELETE FROM cloud_connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM cloud_connections WHERE id = :id")
    suspend fun getById(id: String): CloudConnectionEntity?
}