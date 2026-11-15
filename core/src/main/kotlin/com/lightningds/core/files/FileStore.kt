package com.lightningds.core.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)

/** The few file operations tag writing needs. Paths use "/" and are relative to the store root. */
interface FileStore {
    suspend fun list(path: String): List<FileEntry>
    suspend fun download(path: String, dest: File)
    /** Replaces [path] with [src], writing to a temporary name first so a failed upload can't truncate it. */
    suspend fun replace(path: String, src: File)
    suspend fun write(path: String, bytes: ByteArray)
}

fun joinPath(dir: String, name: String): String = if (dir.isEmpty() || dir == "/") name else dir.trimEnd('/') + "/" + name

/** Music shared over SMB (Windows file sharing), e.g. a Lightning device's USB drive or a NAS. */
class SmbFileStore(
    val host: String,
    val share: String,
    private val user: String,
    private val password: String,
    private val domain: String = "",
) : FileStore, java.io.Closeable {
    private val lock = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var disk: DiskShare? = null

    private suspend fun <T> withShare(block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = disk?.takeIf { it.isConnected && connection?.isConnected == true }
            val d = current ?: run {
                closeQuietly()
                val c = SMBClient(CONFIG)
                val conn = c.connect(host)
                val auth = if (user.isBlank()) AuthenticationContext.guest() else AuthenticationContext(user, password.toCharArray(), domain.ifBlank { null })
                val session = conn.authenticate(auth)
                val share = session.connectShare(share) as? DiskShare ?: error("\\$host\\$share is not a folder share")
                client = c; connection = conn; disk = share
                share
            }
            block(d)
        }
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    override suspend fun list(path: String): List<FileEntry> = withShare { d ->
        d.list(smbPath(path)).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            FileEntry(name, joinPath(path.trim('/'), name), isDir, if (isDir) 0 else info.endOfFile)
        }
    }

    override suspend fun download(path: String, dest: File): Unit = withShare { d ->
        d.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            // Read exactly the file's length: some servers answer a read at end-of-file oddly.
            val size = f.fileInformation.standardInformation.endOfFile
            val buf = ByteArray(256 * 1024)
            dest.outputStream().use { out ->
                var offset = 0L
                while (offset < size) {
                    val n = f.read(buf, offset, 0, minOf(buf.size.toLong(), size - offset).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    offset += n
                }
                if (offset != size) error("Download of $path was incomplete")
            }
        }
        Unit
    }

    override suspend fun replace(path: String, src: File): Unit = withShare { d ->
        val tmp = smbPath("$path.ldstmp")
        d.openFile(tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
            f.outputStream.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
        }
        val uploaded = d.getFileInformation(tmp).standardInformation.endOfFile
        if (uploaded != src.length()) {
            d.rm(tmp)
            error("Upload of $path was incomplete")
        }
        d.openFile(tmp, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            f.rename(smbPath(path), true)
        }
    }

    override suspend fun write(path: String, bytes: ByteArray): Unit = withShare { d ->
        d.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
            f.outputStream.use { it.write(bytes) }
        }
    }

    private fun closeQuietly() {
        runCatching { disk?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        disk = null; connection = null; client = null
    }

    override fun close() = closeQuietly()

    /** Checks that the share can be opened and listed. */
    suspend fun test() { list("") }

    companion object {
        private val CONFIG: SmbConfig = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(35, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
    }
}

/** A local directory, for tests and for files already on the phone. */
class LocalFileStore(private val root: File) : FileStore {
    private fun file(path: String) = if (path.isBlank()) root else File(root, path.trim('/'))

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        file(path).listFiles().orEmpty().map { FileEntry(it.name, joinPath(path.trim('/'), it.name), it.isDirectory, if (it.isDirectory) 0 else it.length()) }
            .filterNot { it.name.startsWith(".") }
    }

    override suspend fun download(path: String, dest: File): Unit = withContext(Dispatchers.IO) { file(path).copyTo(dest, overwrite = true); Unit }

    override suspend fun replace(path: String, src: File): Unit = withContext(Dispatchers.IO) {
        val tmp = File(file(path).path + ".ldstmp")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(file(path))) {
            tmp.delete()
            error("Could not replace $path")
        }
    }

    override suspend fun write(path: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) { file(path).writeBytes(bytes) }
}
