package com.elysium.vanguard.core.smartfolders

import com.elysium.vanguard.core.database.SmartFolderDao
import com.elysium.vanguard.core.database.SmartFolderEntity
import com.elysium.vanguard.core.search.FileFilterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PHASE 2.13 — Smart folder repository.
 *
 * Persists named saved searches and re-evaluates them on demand by walking the
 * configured root directory and feeding each entry through the existing
 * [FileFilterParser]. The walk is shallow (no recursion) by default — recursive
 * walking on Android can be expensive and tends to scan directories the user
 * didn't intend (cache, Android, etc.). Smart folder = "what's in this folder
 * matching this predicate", not "everything on the device matching this
 * predicate". A future `recursive:` filter token can opt into recursion.
 */
class SmartFolderRepository(
    private val dao: SmartFolderDao,
    private val parser: FileFilterParser
) {

    fun observeAll(): Flow<List<SmartFolderEntity>> = dao.observeAll()

    suspend fun listAll(): List<SmartFolderEntity> = dao.listAll()

    suspend fun get(id: Long): SmartFolderEntity? = dao.getById(id)

    suspend fun create(name: String, query: String, rootPath: String): SmartFolderEntity {
        val entity = SmartFolderEntity(
            name = name.trim(),
            query = query.trim(),
            rootPath = rootPath.trim(),
            createdAt = System.currentTimeMillis(),
            lastEvaluatedAt = null
        )
        val newId = dao.insert(entity)
        return entity.copy(id = newId)
    }

    suspend fun rename(folder: SmartFolderEntity, newName: String) {
        dao.update(folder.copy(name = newName.trim()))
    }

    suspend fun delete(folder: SmartFolderEntity) {
        dao.deleteById(folder.id)
    }

    /**
     * Evaluate the smart folder against the live filesystem. Returns the matching
     * files (in whatever order the OS hands them; the caller can sort by name /
     * date / size if needed). Also updates `lastEvaluatedAt` as a side effect so
     * the UI can show "last checked".
     */
    suspend fun evaluate(folder: SmartFolderEntity): List<File> = withContext(Dispatchers.IO) {
        val filter = parser.parse(folder.query)
        val root = File(folder.rootPath)
        if (!root.isDirectory) return@withContext emptyList()

        val children = root.listFiles()?.toList() ?: emptyList()
        val matches = children.filter { parser.matches(it, filter) }
        dao.update(folder.copy(lastEvaluatedAt = System.currentTimeMillis()))
        matches
    }
}