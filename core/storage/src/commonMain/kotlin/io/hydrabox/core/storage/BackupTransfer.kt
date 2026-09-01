package io.hydrabox.core.storage

import io.hydrabox.core.diagnostics.SecretOpener
import io.hydrabox.core.diagnostics.SecretSealer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A backup that can leave the device.
 *
 * [StorageBackup] is not portable: its secret fields are sealed with a key that lives in the
 * platform keystore and never leaves it, so restoring such a file on another device — or
 * even after a reinstall — would produce subscriptions nobody can open. This turns a stored
 * backup into a document by opening every secret with the local key, and turns a document
 * back into storage by sealing them again with whatever key the new device has.
 *
 * The document therefore carries keys in the clear. That is why the platform never writes it
 * to a file as it is: it encrypts it with a passphrase the person supplies.
 */
class BackupTransfer(private val sealer: SecretSealer, private val opener: SecretOpener) {
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(backup: StorageBackup): String = buildString {
        appendLine("hydrabox.backup.v1")
        appendLine(backup.schemaVersion.toString())
        backup.settings.forEach { setting ->
            val secret = setting.secretValue?.let { escape(opener.open(it)) } ?: ""
            appendLine("s\t${setting.key}\t${escape(setting.value)}\t$secret")
        }
        backup.metadata.forEach { entry ->
            appendLine("m\t${entry.key}\t${Base64.encode(entry.value)}")
        }
        backup.subscriptions.forEach { subscription ->
            appendLine(
                "u\t${subscription.id}\t${escape(subscription.name)}\t" +
                    "${escape(opener.open(subscription.sourceSecret))}\t${subscription.updatedAtMillis}",
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(document: String): StorageBackup {
        val lines = document.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == "hydrabox.backup.v1") { "not_a_hydrabox_backup" }
        val schemaVersion = lines.getOrNull(1)?.toLongOrNull() ?: error("backup_has_no_schema_version")
        val settings = mutableListOf<BackupSetting>()
        val metadata = mutableListOf<BackupMetadata>()
        val subscriptions = mutableListOf<BackupSubscription>()
        lines.drop(2).forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "s" -> settings += BackupSetting(
                    key = parts[1],
                    value = unescape(parts[2]),
                    secretValue = parts.getOrNull(3)?.takeIf(String::isNotEmpty)
                        ?.let { sealer.seal(unescape(it)) },
                )
                "m" -> metadata += BackupMetadata(parts[1], Base64.decode(parts[2]))
                "u" -> subscriptions += BackupSubscription(
                    id = parts[1],
                    name = unescape(parts[2]),
                    sourceSecret = sealer.seal(unescape(parts[3])),
                    updatedAtMillis = parts[4].toLong(),
                )
            }
        }
        return StorageBackup(schemaVersion, settings, metadata, subscriptions)
    }

    // Values are user text: a tab or a newline inside one would otherwise end the record.
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                out.append(char)
                index += 1
                continue
            }
            when (value[index + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                '\\' -> out.append('\\')
                else -> out.append(value[index + 1])
            }
            index += 2
        }
        return out.toString()
    }
}
