package io.hydrabox.platform.android

import android.content.Context
import android.util.AtomicFile
import io.hydrabox.core.ruleset.AdBlockFilter
import io.hydrabox.core.ruleset.RuleSetPaths
import io.hydrabox.core.ruleset.RuleSetStatus
import io.hydrabox.core.ruleset.RuleSetWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

/**
 * Downloads the advertising filter and compiles it into rule sets the core can load.
 *
 * Carried over from HydraBox 1.x (`lib/data/adblock/ad_block_rule_set_service.dart`): the same
 * source list, the same 16 MiB ceiling, the same two artefacts — a block set and an allow set
 * — and the same rule that a missing file means the feature is unavailable rather than
 * silently off. What is intentionally different: no progress notifier, because the download
 * happens on a background thread while the screen shows one busy row.
 */
object AdBlockRuleSets {
    private const val SOURCE_URL =
        "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private const val TIMEOUT_MILLIS = 30_000
    private const val DIRECTORY = "rulesets"
    private const val BLOCK_FILE = "adguard_dns_block.srs"
    private const val ALLOW_FILE = "adguard_dns_allow.srs"
    private const val METADATA_FILE = "adguard_dns.meta"
    private const val CURRENT_FILE = "current"
    private const val GENERATION_PREFIX = "generation-"
    private const val LEASE_FILE = ".lease"
    private const val UPDATE_LOCK_FILE = ".update.lock"

    class Lease internal constructor(
        val paths: RuleSetPaths?,
        private val closeLease: () -> Unit,
    ) : AutoCloseable {
        override fun close() = closeLease()
    }

    fun paths(context: Context): RuleSetPaths? {
        val block = File(publishedDirectory(context), BLOCK_FILE)
        if (!block.isFile) return null
        val allow = File(block.parentFile, ALLOW_FILE).takeIf(File::isFile)
        return RuleSetPaths(block.absolutePath, allow?.absolutePath)
    }

    fun status(context: Context): RuleSetStatus {
        val published = publishedDirectory(context)
        val block = File(published, BLOCK_FILE)
        val metadata = File(published, METADATA_FILE)
        if (!block.isFile || !metadata.isFile) return RuleSetStatus.Unavailable
        val fields = runCatching {
            metadata.readText().lineSequence().mapNotNull { line ->
                val name = line.substringBefore('=', "")
                val value = line.substringAfter('=', "")
                if (name.isEmpty()) null else name to value
            }.toMap()
        }.getOrDefault(emptyMap())
        return RuleSetStatus(
            available = true,
            blockedDomains = fields["blocked"]?.toIntOrNull() ?: 0,
            allowedDomains = fields["allowed"]?.toIntOrNull() ?: 0,
            updatedAtMillis = fields["updated"]?.toLongOrNull(),
            bytes = block.length() + File(published, ALLOW_FILE).let { if (it.isFile) it.length() else 0 },
        )
    }

    /**
     * Downloads, compiles and replaces the sets. Blocking on purpose: the caller already runs
     * on a background thread, and a half-written rule set must never be visible, which is why
     * each file lands through a temporary name.
     */
    @Synchronized
    fun update(context: Context): RuleSetStatus {
        val source = download()
        val lists = AdBlockFilter.parse(source)
        check(lists.blocked.isNotEmpty()) { "the filter list contained no usable domain" }
        val block = RuleSetWriter.write(lists.blocked, ::deflate)
        val allow = lists.allowed.takeIf { it.isNotEmpty() }?.let { RuleSetWriter.write(it, ::deflate) }
        val root = directory(context).apply { mkdirs() }
        withUpdateLock(root) {
            clean(root)
            // Build an entire generation before changing the one small pointer readers use. A core
            // already running against an old config keeps its old files; a new config sees all three.
            val generation = File(root, "$GENERATION_PREFIX${System.nanoTime()}").apply { mkdirs() }
            File(generation, BLOCK_FILE).writeBytes(block)
            if (allow != null) File(generation, ALLOW_FILE).writeBytes(allow)
            File(generation, METADATA_FILE).writeBytes(
                buildString {
                    appendLine("blocked=${lists.blocked.size}")
                    appendLine("allowed=${lists.allowed.size}")
                    appendLine("updated=${System.currentTimeMillis()}")
                    appendLine("source=${source.length}")
                }.encodeToByteArray(),
            )
            AtomicFile(File(root, CURRENT_FILE)).run {
                val output = startWrite()
                try {
                    output.write(generation.name.encodeToByteArray())
                    finishWrite(output)
                } catch (failure: Throwable) {
                    failWrite(output)
                    throw failure
                }
            }
            clean(root)
        }
        return status(context)
    }

    fun clear(context: Context) {
        val root = directory(context)
        if (!root.isDirectory) return
        withUpdateLock(root) {
            AtomicFile(File(root, CURRENT_FILE)).delete()
            clean(root)
        }
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    fun acquire(context: Context): Lease = acquire(directory(context))

    internal fun acquire(root: File): Lease {
        val generation = publishedDirectory(root) ?: root
        val block = File(generation, BLOCK_FILE)
        if (!block.isFile) return Lease(null) {}
        val channel = FileOutputStream(File(generation, LEASE_FILE), true).channel
        return try {
            val lock = channel.lock()
            if (!block.isFile) {
                lock.release()
                channel.close()
                Lease(null) {}
            } else {
                val allow = File(generation, ALLOW_FILE).takeIf(File::isFile)
                Lease(RuleSetPaths(block.absolutePath, allow?.absolutePath)) {
                    runCatching { lock.release() }
                    runCatching { channel.close() }
                }
            }
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            throw failure
        }
    }

    internal fun clean(root: File, current: File? = publishedDirectory(root)) {
        root.listFiles()?.forEach { generation ->
            if (!generation.name.startsWith(GENERATION_PREFIX) || generation == current || !generation.isDirectory) return@forEach
            val canonical = runCatching { generation.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != root.canonicalFile) return@forEach
            FileChannel.open(File(canonical, LEASE_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) {
                    try { canonical.deleteRecursively() } finally { lock.release() }
                }
            }
        }
    }

    private fun <T> withUpdateLock(root: File, block: () -> T): T =
        FileChannel.open(File(root, UPDATE_LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }

    private fun publishedDirectory(context: Context): File {
        return publishedDirectory(directory(context)) ?: directory(context)
    }

    private fun publishedDirectory(root: File): File? {
        val current = runCatching { AtomicFile(File(root, CURRENT_FILE)).readFully().decodeToString() }
            .getOrNull()?.takeIf(String::isNotEmpty)
        return current?.let { name ->
            File(root, name).takeIf { candidate ->
                candidate.name.startsWith(GENERATION_PREFIX) &&
                    runCatching { candidate.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
            }
        }
    }

    private fun download(): String {
        val connection = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "HydraBox/2.0.0-alpha1")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "the filter list answered with HTTP ${connection.responseCode}"
            }
            val stream = connection.inputStream.let {
                if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(it) else it
            }
            val buffer = ByteArrayOutputStream()
            stream.use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    check(buffer.size() + read <= MAX_SOURCE_BYTES) { "the filter list exceeds 16 MiB" }
                    buffer.write(chunk, 0, read)
                }
            }
            return buffer.toByteArray().decodeToString()
        } catch (error: IOException) {
            throw IllegalStateException("could not download the filter list", error)
        } finally {
            connection.disconnect()
        }
    }

    /** zlib, at the level 1.x used, because the core reads a zlib-wrapped body. */
    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2)
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(chunk)
                out.write(chunk, 0, written)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
