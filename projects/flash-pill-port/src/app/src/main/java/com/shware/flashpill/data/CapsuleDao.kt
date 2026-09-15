package com.shware.flashpill.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CapsuleDao {

    @Query("SELECT * FROM capsules ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<Capsule>>

    /** 搜索（text 模糊）+ 色标筛选（tag 为 null 表示全部） */
    @Query(
        "SELECT * FROM capsules WHERE (:query = '' OR text LIKE '%' || :query || '%') " +
                "AND (:tag IS NULL OR colorTag = :tag) " +
                "ORDER BY pinned DESC, done ASC, createdAt DESC"
    )
    fun observeFiltered(query: String, tag: String?): Flow<List<Capsule>>

    @Query("SELECT * FROM capsules WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Capsule?

    @Insert
    suspend fun insert(capsule: Capsule)

    @Update
    suspend fun update(capsule: Capsule)

    @Delete
    suspend fun delete(capsule: Capsule)
}