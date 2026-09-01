package io.hydrabox.platform.android

import android.content.Context
import io.hydrabox.core.ruleset.AdBlockFilter
import io.hydrabox.core.ruleset.RuleSetPaths
import io.hydrabox.core.ruleset.RuleSetStatus
import io.hydrabox.core.ruleset.RuleSetWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

    fun paths(context: Context): RuleSetPaths? {
        val block = File(directory(context), BLOCK_FILE)
        if (!block.isFile) return null
        val allow = File(directory(context), ALLOW_FILE).takeIf(File::isFile)
        return RuleSetPaths(block.absolutePath, allow?.absolutePath)
    }

    fun status(context: Context): RuleSetStatus {
        val block = File(directory(context), BLOCK_FILE)
        val metadata = File(directory(context), METADATA_FILE)
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
            bytes = block.length() + File(directory(context), ALLOW_FILE).let { if (it.isFile) it.length() else 0 },
        )
    }

    /**
     * Downloads, compiles and replaces the sets. Blocking on purpose: the caller already runs
     * on a background thread, and a half-written rule set must never be visible, which is why
     * each file lands through a temporary name.
     */
    fun update(context: Context): RuleSetStatus {
        val source = download()
        val lists = AdBlockFilter.parse(source)
        check(lists.blocked.isNotEmpty()) { "the filter list contained no usable domain" }
        val block = RuleSetWriter.write(lists.blocked, ::deflate)
        val allow = lists.allowed.takeIf { it.isNotEmpty() }?.let { RuleSetWriter.write(it, ::deflate) }
        val directory = directory(context).apply { mkdirs() }
        replace(File(directory, BLOCK_FILE), block)
        if (allow == null) File(directory, ALLOW_FILE).delete() else replace(File(directory, ALLOW_FILE), allow)
        replace(
            File(directory, METADATA_FILE),
            buildString {
                appendLine("blocked=${lists.blocked.size}")
                appendLine("allowed=${lists.allowed.size}")
                appendLine("updated=${System.currentTimeMillis()}")
                appendLine("source=${source.length}")
            }.encodeToByteArray(),
        )
        return status(context)
    }

    fun clear(context: Context) {
        listOf(BLOCK_FILE, ALLOW_FILE, METADATA_FILE).forEach { File(directory(context), it).delete() }
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    private fun replace(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.part")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target) || (target.delete() && temporary.renameTo(target))) {
            "could not replace ${target.name}"
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
