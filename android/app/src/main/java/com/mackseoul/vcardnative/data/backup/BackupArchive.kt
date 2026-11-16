package com.mackseoul.vcardnative.data.backup

import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.data.profile.StrictJson
import com.mackseoul.vcardnative.data.profile.imageAssets
import com.mackseoul.vcardnative.data.profile.jsonObject
import com.mackseoul.vcardnative.data.profile.long
import com.mackseoul.vcardnative.data.profile.sha256
import com.mackseoul.vcardnative.data.profile.string
import com.mackseoul.vcardnative.domain.backup.BackupException
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.backup.RestoreCandidate
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.ImageAsset
import com.mackseoul.vcardnative.domain.profile.ImageRole
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.CRC32

object BackupArchive {
    const val MAX_ARCHIVE_BYTES = 25_165_824
    const val MAX_ENTRY_BYTES = 20_971_520
    const val MAX_MANIFEST_BYTES = 65_536
    const val MAX_DATA_BYTES = 524_288
    const val MAX_IMAGE_BYTES = 8_388_608
    const val MAX_KEY_BYTES = 65_536

    private val limits = mapOf(
        "manifest.json" to MAX_MANIFEST_BYTES, "data.json" to MAX_DATA_BYTES,
        "profile.jpg" to MAX_IMAGE_BYTES, "profile.png" to MAX_IMAGE_BYTES,
        "cover.jpg" to MAX_IMAGE_BYTES, "cover.png" to MAX_IMAGE_BYTES,
        "public-key.asc" to MAX_KEY_BYTES,
    )

    fun create(snapshot: BackupSnapshot): ByteArray = guarded {
        val data = snapshot.data
        val images = snapshot.images
        val key = snapshot.publicKey
        validateAssets(data, images, key)
        val entries = linkedMapOf("data.json" to BackupDataCodec.encode(data))
        imageAssets(data).forEach { (role, asset) -> entries[imagePath(role, asset)] = images.getValue(role) }
        key?.let { entries["public-key.asc"] = it }
        val manifest = StrictJson.encode(linkedMapOf(
            "format" to "vcard-native-backup", "formatVersion" to 1,
            "appSchemaVersion" to AppData.CURRENT_SCHEMA_VERSION,
            "createdAt" to snapshot.capturedAt.toString(),
            "files" to entries.map { (path, content) ->
                linkedMapOf("path" to path, "mediaType" to mime(path),
                    "byteLength" to content.size, "sha256" to sha256(content))
            },
        ))
        require(manifest.size <= MAX_MANIFEST_BYTES)
        Zip32.write(linkedMapOf("manifest.json" to manifest).apply { putAll(entries) })
    }

    fun validate(bytes: ByteArray): RestoreCandidate = guarded {
        require(bytes.size <= MAX_ARCHIVE_BYTES)
        val entries = Zip32.read(bytes.copyOf())
        val manifestBytes = requireNotNull(entries["manifest.json"])
        val manifest = StrictJson.decode(manifestBytes, maxDepth = 16, allowArrays = true).jsonObject(
            "format", "formatVersion", "appSchemaVersion", "createdAt", "files")
        require(manifest.string("format") == "vcard-native-backup")
        val appSchemaVersion = manifest.long("appSchemaVersion").also {
            require(it in 1L..AppData.CURRENT_SCHEMA_VERSION.toLong())
        }
        require(manifest.long("formatVersion") == 1L)
        val time = manifest.string("createdAt")
        require(time.length <= 40 && time.endsWith('Z'))
        val createdAt = Instant.parse(time)
        val data = BackupDataCodec.decode(requireNotNull(entries["data.json"]))
        require(data.schemaVersion.toLong() == appSchemaVersion)
        val paths = listOf("data.json") + imageAssets(data).map { imagePath(it.key, it.value) } +
            if (data.profile.publicKey != null) listOf("public-key.asc") else emptyList()
        require(entries.keys == (listOf("manifest.json") + paths).toSet())
        val inventory = manifest["files"] as? List<*> ?: error("Invalid file inventory.")
        require(inventory.size == paths.size)
        inventory.zip(paths).forEach { (raw, path) ->
            val item = raw.jsonObject("path", "mediaType", "byteLength", "sha256")
            val content = entries.getValue(path)
            require(item.string("path") == path && item.string("mediaType") == mime(path))
            require(item.long("byteLength") == content.size.toLong())
            val hash = item.string("sha256")
            require(hash.matches(Regex("[0-9a-f]{64}")) && hash == sha256(content))
        }
        val images = imageAssets(data).mapValues { (role, asset) -> entries.getValue(imagePath(role, asset)) }
        val key = entries["public-key.asc"]
        validateAssets(data, images, key)
        RestoreCandidate(data, images, key, createdAt)
    }

    private fun validateAssets(data: AppData, images: Map<ImageRole, ByteArray>, key: ByteArray?) {
        val assets = imageAssets(data)
        require(images.keys == assets.keys)
        val ids = assets.values.map { it.assetId } + listOfNotNull(data.profile.publicKey?.assetId)
        require(ids.size == ids.toSet().size)
        assets.forEach { (role, asset) -> ImageStructure.validateCanonical(images.getValue(role), asset, role) }
        require((key != null) == (data.profile.publicKey != null))
        data.profile.publicKey?.let { PublicKeyValidator.validateStored(requireNotNull(key), it) }
    }

    private fun imagePath(role: ImageRole, asset: ImageAsset): String =
        (if (role == ImageRole.Portrait) "profile" else "cover") +
            when (asset.mediaType) { "image/jpeg" -> ".jpg"; "image/png" -> ".png"; else -> error("Invalid image MIME.") }

    private fun mime(path: String): String = when {
        path.endsWith(".json") -> "application/json"
        path.endsWith(".jpg") -> "image/jpeg"
        path.endsWith(".png") -> "image/png"
        path == "public-key.asc" -> "application/pgp-keys"
        else -> error("Invalid backup entry.")
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException(cause = e)
    }

    /** Narrow framing parser: every local record must exactly tile the bytes before the central directory. */
    private object Zip32 {
        private data class Entry(
            val name: String, val flags: Int, val version: Int, val time: Int, val date: Int,
            val crc: Long, val size: Int, val offset: Int,
        )

        fun read(bytes: ByteArray): Map<String, ByteArray> {
            require(bytes.size in 22..MAX_ARCHIVE_BYTES)
            val b = Reader(bytes)
            val end = bytes.size - 22
            require(b.u32(end) == 0x06054b50L && b.u16(end + 20) == 0)
            require(b.u16(end + 4) == 0 && b.u16(end + 6) == 0)
            val count = b.u16(end + 10)
            require(count in 2..5 && b.u16(end + 8) == count)
            val centralSize = b.u32(end + 12)
            val centralOffset = b.u32(end + 16)
            require(centralOffset + centralSize == end.toLong())
            require(centralOffset <= end && centralSize >= count * 46L)
            var p = centralOffset.toInt()
            val records = mutableListOf<Entry>()
            val names = mutableSetOf<String>()
            var total = 0L
            repeat(count) {
                require(b.u32(p) == 0x02014b50L)
                b.range(p, 46, end)
                val madeBy = b.u16(p + 4)
                val version = b.u16(p + 6)
                val flags = b.u16(p + 8)
                require(version in 10..20 && flags in setOf(0, 0x800) && b.u16(p + 10) == 0)
                require(b.u16(p + 30) == 0 && b.u16(p + 32) == 0 && b.u16(p + 34) == 0)
                require(b.u16(p + 36) and 0xfffe == 0)
                attributes(madeBy, b.u32(p + 38))
                val nameLength = b.u16(p + 28)
                val name = b.name(p + 46, nameLength, end)
                require(names.add(name))
                val size = b.u32(p + 24)
                require(size == b.u32(p + 20) && size <= limits.getValue(name))
                total += size
                require(total <= MAX_ENTRY_BYTES)
                val offset = b.u32(p + 42)
                require(offset < centralOffset)
                records.add(Entry(name, flags, version, b.u16(p + 12), b.u16(p + 14),
                    b.u32(p + 16), size.toInt(), offset.toInt()))
                p += 46 + nameLength
            }
            require(p == end)
            val result = linkedMapOf<String, ByteArray>()
            var next = 0
            records.sortedBy { it.offset }.forEach { entry ->
                val start = entry.offset
                require(start == next && b.u32(start) == 0x04034b50L)
                b.range(start, 30, centralOffset.toInt())
                require(b.u16(start + 4) == entry.version && b.u16(start + 6) == entry.flags)
                require(b.u16(start + 8) == 0 && b.u16(start + 10) == entry.time &&
                    b.u16(start + 12) == entry.date)
                require(b.u32(start + 14) == entry.crc && b.u32(start + 18) == entry.size.toLong() &&
                    b.u32(start + 22) == entry.size.toLong() && b.u16(start + 28) == 0)
                val nameLength = b.u16(start + 26)
                require(b.name(start + 30, nameLength, centralOffset.toInt()) == entry.name)
                val contentStart = start + 30 + nameLength
                b.range(contentStart, entry.size, centralOffset.toInt())
                next = contentStart + entry.size
                val content = bytes.copyOfRange(contentStart, next)
                require(CRC32().apply { update(content) }.value == entry.crc)
                result[entry.name] = content
            }
            require(next == centralOffset.toInt())
            return result
        }

        private fun attributes(madeBy: Int, attributes: Long) {
            require(madeBy and 255 in 10..20)
            val host = madeBy ushr 8
            require(host == 0 || host == 3)
            val dos = attributes and 0xffff
            require(dos and 0x27.inv().toLong() == 0L)
            val mode = (attributes ushr 16).toInt()
            if (host == 0) require(mode == 0) else {
                require(mode and 0xf000 in setOf(0, 0x8000))
                require(mode and 0x0e49 == 0) // No set-id, sticky or executable permissions.
            }
        }

        fun write(entries: Map<String, ByteArray>): ByteArray {
            require(entries.size in 2..5 && entries.values.sumOf { it.size.toLong() } <= MAX_ENTRY_BYTES)
            val out = Writer()
            val records = mutableListOf<Entry>()
            entries.forEach { (name, content) ->
                require(content.size <= limits.getValue(name))
                val record = Entry(name, 0, 20, 0, 33, CRC32().apply { update(content) }.value,
                    content.size, out.size())
                records.add(record)
                out.u32(0x04034b50); out.u16(20); out.u16(0); out.u16(0); out.u16(0); out.u16(33)
                out.u32(record.crc); out.u32(content.size.toLong()); out.u32(content.size.toLong())
                out.u16(name.length); out.u16(0); out.write(name.toByteArray(Charsets.US_ASCII)); out.write(content)
            }
            val centralOffset = out.size()
            records.forEach { entry ->
                out.u32(0x02014b50); out.u16(20); out.u16(20); out.u16(0); out.u16(0); out.u16(0); out.u16(33)
                out.u32(entry.crc); out.u32(entry.size.toLong()); out.u32(entry.size.toLong())
                out.u16(entry.name.length); out.u16(0); out.u16(0); out.u16(0); out.u16(0); out.u32(0)
                out.u32(entry.offset.toLong()); out.write(entry.name.toByteArray(Charsets.US_ASCII))
            }
            val centralSize = out.size() - centralOffset
            out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(records.size); out.u16(records.size)
            out.u32(centralSize.toLong()); out.u32(centralOffset.toLong()); out.u16(0)
            return out.toByteArray().also { require(it.size <= MAX_ARCHIVE_BYTES) }
        }

        private class Reader(val bytes: ByteArray) {
            fun range(offset: Int, size: Int, end: Int = bytes.size) {
                require(offset >= 0 && size >= 0 && offset.toLong() + size <= end && end <= bytes.size)
            }
            fun u16(p: Int): Int {
                range(p, 2)
                return (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
            }
            fun u32(p: Int): Long {
                range(p, 4)
                return (0..3).fold(0L) { value, i -> value or ((bytes[p + i].toLong() and 255) shl (8 * i)) }
            }
            fun name(p: Int, length: Int, end: Int): String {
                require(length in 1..14)
                range(p, length, end)
                require((p until p + length).all { bytes[it].toInt() in 32..126 })
                return String(bytes, p, length, Charsets.US_ASCII).also { require(it in limits) }
            }
        }

        private class Writer : ByteArrayOutputStream() {
            fun u16(value: Int) { write(value and 255); write((value ushr 8) and 255) }
            fun u32(value: Long) { repeat(4) { write(((value ushr (it * 8)) and 255).toInt()) } }
        }
    }
}
