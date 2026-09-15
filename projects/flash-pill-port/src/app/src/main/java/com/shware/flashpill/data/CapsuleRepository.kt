package com.shware.flashpill.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class CapsuleRepository private constructor(private val dao: CapsuleDao) {

    fun observeAll(): Flow<List<Capsule>> = dao.observeAll()

    fun observeFiltered(query: String, tag: String?): Flow<List<Capsule>> =
        dao.observeFiltered(query, tag)

    suspend fun add(capsule: Capsule) = dao.insert(capsule)

    suspend fun update(capsule: Capsule) =
        dao.update(capsule.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(capsule: Capsule) = dao.delete(capsule)

    suspend fun find(id: String): Capsule? = dao.findById(id)

    companion object {
        @Volatile
        private var INSTANCE: CapsuleRepository? = null

        fun get(context: Context): CapsuleRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CapsuleRepository(
                    AppDatabase.get(context).capsuleDao()
                ).also { INSTANCE = it }
            }
    }
}