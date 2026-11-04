package io.zer0.memory.summary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.zer0.common.Logger
import io.zer0.memory.fact.MemoryLegacyReset
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Room 按降级策略重建 MemoryDb 前，先在同目录生成可恢复快照。
 *
 * 主库与 WAL sidecar 会先复制到临时目录并校验字节一致，最后原子发布目录；
 * guard 不会移动或删除正在使用的数据库文件。
 */
internal object MemoryDbDowngradeGuard {
    private const val TAG = "MemoryDbDowngradeGuard"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Synchronized
    fun archiveIfNewer(context: Context, databaseName: String, currentVersion: Int): File? {
        val databaseFile = context.getDatabasePath(databaseName)
        if (databaseFile.isFile) {
            val storedVersion = readVersion(databaseFile)
            if (storedVersion > currentVersion) {
                return archiveNewerDatabase(context, databaseFile, databaseName, storedVersion, currentVersion)
            }
        }
        return null
    }

    private fun readVersion(databaseFile: File): Int {
        val header = ByteArray(SQLITE_HEADER.size)
        val bytesRead = databaseFile.inputStream().use { it.read(header) }
        check(bytesRead == SQLITE_HEADER.size && header.contentEquals(SQLITE_HEADER)) {
            "Memory database has an invalid SQLite header; refusing destructive downgrade"
        }
        return try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { it.version }
        } catch (error: SQLiteException) {
            throw IllegalStateException(
                "Cannot verify memory database version; refusing to open it destructively",
                error,
            )
        }
    }

    private fun archiveNewerDatabase(
        context: Context,
        databaseFile: File,
        databaseName: String,
        storedVersion: Int,
        currentVersion: Int,
    ): File {
        checkpointWal(databaseFile)
        val archiveDirectory = nextArchiveDirectory(databaseFile, storedVersion)
        val stagingDirectory = File(
            archiveDirectory.parentFile,
            ".${archiveDirectory.name}.${UUID.randomUUID()}.pending",
        )
        check(stagingDirectory.mkdir()) {
            "Cannot create memory database archive staging directory"
        }
        val files = listOf(
            databaseFile,
            File("${databaseFile.absolutePath}-wal"),
            File("${databaseFile.absolutePath}-shm"),
        ).filter(File::exists)

        try {
            verifySourceDatabase(databaseFile, storedVersion)
            files.forEach { source ->
                val copy = source.copyTo(File(stagingDirectory, source.name), overwrite = false)
                check(copy.length() == source.length() && sha256(copy).contentEquals(sha256(source))) {
                    "Memory database archive copy did not match its source: ${source.name}"
                }
            }
            check(stagingDirectory.renameTo(archiveDirectory)) {
                "Cannot publish complete memory database archive"
            }
        } catch (error: IOException) {
            failArchive(stagingDirectory, error)
        } catch (error: IllegalStateException) {
            failArchive(stagingDirectory, error)
        } catch (error: SecurityException) {
            failArchive(stagingDirectory, error)
        } catch (error: SQLiteException) {
            failArchive(stagingDirectory, error)
        }

        MemoryLegacyReset.mark(context, databaseName)
        Logger.w(TAG, "MemoryDb v$storedVersion archived; about to open v$currentVersion")
        return archiveDirectory
    }

    private fun verifySourceDatabase(databaseFile: File, expectedVersion: Int) {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            check(database.version == expectedVersion) {
                "Memory database version changed while preparing its archive"
            }
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Memory database failed SQLite quick_check before downgrade"
                }
            }
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun failArchive(stagingDirectory: File, cause: Exception): Nothing {
        stagingDirectory.deleteRecursively()
        throw MemoryDatabaseDestructiveFallbackException(cause)
    }

    private fun checkpointWal(databaseFile: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "WAL checkpoint reported busy"
                    }
                }
            }
        }.onFailure { error ->
            // The original WAL and SHM are archived with the main file if checkpointing fails.
            Logger.w(TAG, "WAL checkpoint incomplete; archiving together with the sidecars", error)
        }
    }

    private fun nextArchiveDirectory(databaseFile: File, storedVersion: Int): File {
        val parent = checkNotNull(databaseFile.parentFile)
        val baseName = "${databaseFile.name}.pre-destructive.v$storedVersion.bak"
        val preferred = File(parent, baseName)
        if (!preferred.exists()) {
            return preferred
        }
        var suffix = 1
        while (true) {
            val candidate = File(parent, "$baseName.$suffix")
            if (!candidate.exists()) return candidate
            suffix += 1
        }
    }

    private class MemoryDatabaseDestructiveFallbackException(cause: Throwable) :
        IllegalStateException(
            "Cannot safely archive newer memory database; refusing destructive downgrade",
            cause,
        )
}
