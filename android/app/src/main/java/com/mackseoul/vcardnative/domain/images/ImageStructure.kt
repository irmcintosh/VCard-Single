package com.mackseoul.vcardnative.domain.images

import com.mackseoul.vcardnative.domain.profile.*
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

class ImageException(message: String = "Choose a complete still JPEG, PNG or WebP within the image limits.",
    cause: Throwable? = null) : Exception(message, cause)

/** Container validation precedes Android decoding; canonical validation also runs on every store read. */
object ImageStructure {
    const val SOURCE_LIMIT = 20 * 1024 * 1024
    const val RETAINED_LIMIT = 8 * 1024 * 1024
    data class Info(val mediaType: String, val width: Int, val height: Int,
        val lossless: Boolean = false, val alpha: Boolean = false, val orientation: Int = 1)

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun validateCanonical(bytes: ByteArray, asset: ImageAsset, role: ImageRole) {
        require(ProfileValidator.validImageDescriptor(asset, role))
        require(bytes.size.toLong() == asset.byteLength && digest(bytes) == asset.sha256)
        val info = inspect(bytes, true)
        require(info.mediaType == asset.mediaType && info.width == asset.width && info.height == asset.height)
    }

    /** Only for fresh encodes of a known sRGB software bitmap, never source-file passthrough. */
    internal fun stripEncoderMetadata(bytes: ByteArray): ByteArray {
        val info = inspect(bytes)
        val out = java.io.ByteArrayOutputStream()
        if(info.mediaType == "image/jpeg") {
            return filterJpeg(bytes) { it in 225..239 || it == 254 }
        } else {
            require(info.mediaType == "image/png")
            out.write(bytes,0,8)
            var p = 8
            while(p < bytes.size) {
                val n = be(bytes,p)
                val type = text(bytes,p+4,4)
                if(type in setOf("IHDR","PLTE","tRNS","IDAT","IEND","sBIT")) out.write(bytes,p,n+12)
                if(type == "IHDR") {
                    val chunk = java.io.DataOutputStream(out)
                    chunk.writeInt(1);chunk.writeBytes("sRGB");chunk.writeByte(0)
                    chunk.writeInt(CRC32().apply { update("sRGB".toByteArray());update(0) }.value.toInt())
                }
                p += n+12
            }
        }
        return out.toByteArray()
    }

    internal fun withoutExif(bytes: ByteArray, info: Info): ByteArray {
        if(info.mediaType == "image/jpeg") return filterJpeg(bytes) { it == 225 }
        val out = java.io.ByteArrayOutputStream()
        if(info.mediaType == "image/png") {
            out.write(bytes,0,8)
            var p = 8
            while(p < bytes.size) {
                val n = be(bytes,p)
                if(text(bytes,p+4,4) != "eXIf") out.write(bytes,p,n+12)
                p += n+12
            }
        } else {
            out.write(bytes,0,12)
            var p = 12
            while(p < bytes.size) {
                val n = le(bytes,p+4);val length = 8+n+n%2
                if(text(bytes,p,4) != "EXIF") {
                    val chunk = bytes.copyOfRange(p,p+length)
                    if(text(bytes,p,4) == "VP8X") chunk[8]=(u(chunk,8) and 8.inv()).toByte()
                    out.write(chunk)
                }
                p += length
            }
            val result = out.toByteArray()
            repeat(4) { result[4+it]=((result.size-8) ushr (it*8)).toByte() }
            return result
        }
        return out.toByteArray()
    }

    private fun filterJpeg(bytes: ByteArray, remove: (Int) -> Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes,0,2)
        var p = 2
        while(p < bytes.size) {
            val begin = p
            require(u(bytes,p++) == 255)
            while(u(bytes,p) == 255) p++
            val marker = u(bytes,p++)
            if(marker == 217) { out.write(bytes,begin,p-begin);break }
            val n = (u(bytes,p) shl 8) or u(bytes,p+1)
            if(!remove(marker)) out.write(bytes,begin,p+n-begin)
            p += n
            if(marker == 218) {
                val start = p
                while(p < bytes.size) {
                    if(u(bytes,p) != 255) { p++;continue }
                    val next = u(bytes,p+1)
                    if(next == 0 || next in 208..215) { p+=2;continue }
                    if(next == 255) { p++;continue }
                    break
                }
                out.write(bytes,start,p-start)
            }
        }
        return out.toByteArray()
    }

    fun inspect(b: ByteArray, canonical: Boolean = false): Info {
        require(b.isNotEmpty() && b.size <= if (canonical) RETAINED_LIMIT else SOURCE_LIMIT)
        val info = when {
            b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> png(b, canonical)
            b.size >= 2 && u(b, 0) == 255 && u(b, 1) == 216 -> jpeg(b, canonical)
            !canonical && b.size >= 12 && text(b, 0, 4) == "RIFF" && text(b, 8, 4) == "WEBP" -> webp(b)
            else -> error("Unsupported image")
        }
        require(info.width in 1..8192 && info.height in 1..8192 &&
            info.width.toLong() * info.height <= 16_777_216L)
        return info
    }

    private fun png(b: ByteArray, canonical: Boolean): Info {
        var p = 8
        var width = 0; var height = 0; var depth = 0; var color = 0; var interlace = 0
        var ended = false; var dataSeen = false; var dataEnded = false; var palette = false; var alpha = false
        var orientation = 1
        val compressed = java.io.ByteArrayOutputStream()
        val unique = mutableSetOf<String>()
        while (p < b.size) {
            require(!ended && b.size - p >= 12)
            val n = be(b, p).also { require(it >= 0 && it <= b.size - p - 12) }
            val type = text(b, p + 4, 4)
            require(type.all { it in 'A'..'Z' || it in 'a'..'z' } && type[2] in 'A'..'Z')
            val crc = CRC32().apply { update(b, p + 4, n + 4) }.value
            require(crc == (be(b, p + 8 + n).toLong() and 0xffffffffL))
            val start = p + 8
            require(width != 0 || type == "IHDR")
            if (type !in setOf("IDAT", "tEXt", "zTXt", "iTXt")) require(unique.add(type))
            if (dataSeen && type != "IDAT") dataEnded = true
            when (type) {
                "IHDR" -> {
                    require(p == 8 && n == 13)
                    width = be(b, start); height = be(b, start + 4)
                    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216L)
                    depth = u(b, start + 8); color = u(b, start + 9)
                    require(when (color) { 0 -> depth in setOf(1,2,4,8,16); 2,4,6 -> depth in setOf(8,16); 3 -> depth in setOf(1,2,4,8); else -> false })
                    require(u(b, start + 10) == 0 && u(b, start + 11) == 0)
                    interlace = u(b, start + 12); require(interlace in 0..1)
                    alpha = color == 4 || color == 6
                }
                "PLTE" -> { require(!dataSeen && n in 3..768 && n % 3 == 0 && color != 0 && color != 4); palette = true }
                "tRNS" -> {
                    require(!dataSeen && color in setOf(0,2,3))
                    require(when(color) { 0 -> n == 2; 2 -> n == 6; else -> palette && n in 1..256 })
                    alpha = true
                }
                "IDAT" -> {
                    require(!dataEnded && (color != 3 || palette))
                    dataSeen = true; compressed.write(b, start, n)
                }
                "IEND" -> { require(dataSeen && n == 0); ended = true }
                "acTL", "fcTL", "fdAT" -> error("Animated PNG")
                "eXIf" -> { require(!canonical); orientation = exif(b.copyOfRange(start, start+n)) }
                "sRGB" -> { require(!dataSeen && n == 1 && u(b,start) <= 3) }
                "gAMA" -> { require(!dataSeen && n == 4 && (!canonical || be(b,start) == 45455)) }
                "cHRM" -> {
                    require(!dataSeen && n == 32)
                    if(canonical) {
                        val srgb = intArrayOf(31270,32900,64000,33000,30000,60000,15000,6000)
                        require(srgb.indices.all { be(b,start+4*it) == srgb[it] })
                    }
                }
                "sBIT" -> {
                    require(!dataSeen)
                    val channels = when(color) { 0 -> 1; 2,3 -> 3; 4 -> 2; else -> 4 }
                    require(n == channels && (start until start+n).all { u(b,it) in 1..if(color == 3) 8 else depth })
                }
                "iCCP", "tEXt", "zTXt", "iTXt", "pHYs", "bKGD", "hIST", "tIME" -> require(!canonical)
                else -> error("Unknown PNG chunk")
            }
            p += n + 12
        }
        require(ended && p == b.size)
        val channels = when(color) { 0,3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        val passes = if (interlace == 0) listOf(intArrayOf(0,0,1,1)) else listOf(
            intArrayOf(0,0,8,8), intArrayOf(4,0,8,8), intArrayOf(0,4,4,8),
            intArrayOf(2,0,4,4), intArrayOf(0,2,2,4), intArrayOf(1,0,2,2), intArrayOf(0,1,1,2))
        val inflater = Inflater()
        try {
            inflater.setInput(compressed.toByteArray())
            val row = ByteArray(8192 * 8 + 1)
            for (pass in passes) {
                val w = if(width <= pass[0]) 0 else (width-pass[0]+pass[2]-1)/pass[2]
                val h = if(height <= pass[1]) 0 else (height-pass[1]+pass[3]-1)/pass[3]
                if(w == 0 || h == 0) continue
                val length = (w * channels * depth + 7)/8 + 1
                repeat(h) {
                    var count = 0
                    while(count < length) {
                        val got = inflater.inflate(row,count,length-count)
                        require(got > 0); count += got
                    }
                    require(u(row,0) <= 4)
                }
            }
            require(inflater.inflate(row,0,1) == 0 && inflater.finished() && inflater.remaining == 0)
        } finally { inflater.end() }
        return Info("image/png",width,height,true,alpha,orientation)
    }

    private fun jpeg(b: ByteArray, canonical: Boolean): Info {
        var p = 2; var width = 0; var height = 0; var scans = 0; var orientation = 1; var exifSeen = false
        var table = false; var huffman = false; var ended = false
        val componentIds = mutableSetOf<Int>()
        while(p < b.size) {
            require(u(b,p++) == 255)
            while(p < b.size && u(b,p) == 255) p++
            require(p < b.size)
            val marker = u(b,p++)
            if(marker == 217) { ended = true; break }
            require(marker != 216 && marker != 0 && marker !in 208..215 && p+2 <= b.size)
            val n = (u(b,p) shl 8) or u(b,p+1)
            require(n >= 2 && n <= b.size-p)
            val start = p+2
            when(marker) {
                192,194 -> {
                    require(width == 0 && n >= 11 && u(b,start) == 8)
                    height = (u(b,start+1) shl 8) or u(b,start+2)
                    width = (u(b,start+3) shl 8) or u(b,start+4)
                    val components = u(b,start+5)
                    require(components in setOf(1,3) && n == 8 + 3*components)
                    repeat(components) {
                        val at = start+6+it*3
                        require(componentIds.add(u(b,at)))
                        val sampling = u(b,at+1)
                        require(sampling ushr 4 in 1..4 && sampling and 15 in 1..4 && u(b,at+2) <= 3)
                    }
                }
                219 -> {
                    var q = start
                    while(q < p+n) {
                        val flags = u(b,q++)
                        val precision = flags ushr 4
                        require(precision in 0..1 && flags and 15 <= 3)
                        val length = 64 * (precision+1)
                        require(q+length <= p+n)
                        for(i in 0 until 64) {
                            val value = if(precision == 0) u(b,q+i) else
                                (u(b,q+i*2) shl 8) or u(b,q+i*2+1)
                            require(value > 0)
                        }
                        q += length
                    }
                    require(q == p+n && q > start); table = true
                }
                196 -> {
                    var q = start
                    while(q < p+n) {
                        require(q+17 <= p+n)
                        val flags = u(b,q++); require(flags ushr 4 in 0..1 && flags and 15 <= 3)
                        var count = 0; var slots = 1
                        repeat(16) {
                            val codes = u(b,q++); count += codes; slots = slots*2-codes; require(slots >= 0)
                        }
                        require(count in 1..256 && q+count <= p+n)
                        q += count
                    }
                    require(q == p+n && q > start); huffman = true
                }
                218 -> {
                    require(width > 0 && table && huffman && n >= 8)
                    val components = u(b,start); require(components in 1..3 && n == 6+2*components)
                    val ids = mutableSetOf<Int>()
                    repeat(components) {
                        val id = u(b,start+1+it*2); val selectors = u(b,start+2+it*2)
                        require(id in componentIds && ids.add(id) && selectors ushr 4 <= 3 && selectors and 15 <= 3)
                    }
                    require(u(b,p+n-3) <= u(b,p+n-2) && u(b,p+n-2) <= 63)
                    require(u(b,p+n-1) ushr 4 <= 13 && u(b,p+n-1) and 15 <= 13)
                    scans++
                }
                221 -> require(n == 4)
                224 -> {
                    require(n >= 16 && text(b,start,5) == "JFIF\u0000")
                    require(n == 16 + 3*u(b,start+12)*u(b,start+13))
                    if(canonical) require(n == 16)
                }
                225 -> {
                    require(!canonical)
                    if(n >= 8 && text(b,start,6) == "Exif\u0000\u0000") {
                        require(!exifSeen);exifSeen = true
                        orientation = exif(b.copyOfRange(start+6,p+n))
                    } else require(n >= 31 && text(b,start,29) == "http://ns.adobe.com/xap/1.0/\u0000")
                }
                226 -> require(!canonical && n >= 16 && text(b,start,12) == "ICC_PROFILE\u0000")
                237,254 -> require(!canonical)
                238 -> require(!canonical && n == 14 && text(b,start,5) == "Adobe")
                else -> error("Unsupported JPEG marker")
            }
            p += n
            if(marker == 218) {
                val beginning = p
                while(p < b.size) {
                    if(u(b,p) != 255) { p++; continue }
                    require(p+1 < b.size)
                    val next = u(b,p+1)
                    if(next == 0 || next in 208..215) { p += 2; continue }
                    if(next == 255) { p++; continue }
                    break
                }
                require(p > beginning)
            }
        }
        require(ended && p == b.size && width > 0 && scans > 0)
        return Info("image/jpeg",width,height,orientation=orientation)
    }

    private fun webp(b: ByteArray): Info {
        require(le(b,4).toLong()+8 == b.size.toLong())
        var p = 12; var info: Info? = null; var extended = false; var alpha = false
        var canvasW = 0; var canvasH = 0; var orientation = 1; var features = 0
        val seen = mutableSetOf<String>()
        while(p < b.size) {
            require(p+8 <= b.size)
            val type = text(b,p,4); val n = le(b,p+4)
            require(n >= 0 && n <= b.size-p-8 && seen.add(type))
            val s = p+8
            when(type) {
                "VP8X" -> {
                    require(p == 12 && n == 10)
                    val flags = u(b,s); require(flags and 0xc3 == 0)
                    require(u(b,s+1) == 0 && u(b,s+2) == 0 && u(b,s+3) == 0)
                    extended = true; features = flags; alpha = flags and 16 != 0
                    canvasW = le24(b,s+4)+1; canvasH = le24(b,s+7)+1
                }
                "VP8 " -> {
                    require(info == null && n >= 10 && u(b,s) and 1 == 0)
                    require(u(b,s+3)==157 && u(b,s+4)==1 && u(b,s+5)==42)
                    info = Info("image/webp", (u(b,s+6) or (u(b,s+7) shl 8)) and 16383,
                        (u(b,s+8) or (u(b,s+9) shl 8)) and 16383,alpha=alpha)
                }
                "VP8L" -> {
                    require(info == null && n >= 5 && u(b,s) == 47)
                    val bits = le(b,s+1)
                    require(bits ushr 29 == 0)
                    info = Info("image/webp",(bits and 16383)+1,((bits ushr 14) and 16383)+1,true,
                        bits and (1 shl 28) != 0)
                }
                "ALPH" -> { require(extended && alpha && info == null && n > 1 && u(b,s) and 0xc0 == 0) }
                "EXIF" -> { require(extended && info != null); orientation = exif(b.copyOfRange(s,s+n).let {
                    if(it.size >= 6 && text(it,0,6) == "Exif\u0000\u0000") it.copyOfRange(6,it.size) else it }) }
                "ICCP" -> require(extended && info == null && n > 0)
                "XMP " -> require(extended && info != null && n > 0)
                else -> error("Unsupported or animated WebP")
            }
            p += 8+n
            if(n % 2 != 0) { require(p < b.size && u(b,p) == 0); p++ }
        }
        val result = requireNotNull(info)
        require(p == b.size && (!extended || result.width == canvasW && result.height == canvasH))
        require(!seen.contains("ALPH") || !result.lossless)
        if(extended) {
            require((features and 32 != 0) == seen.contains("ICCP"))
            require((features and 8 != 0) == seen.contains("EXIF"))
            require((features and 4 != 0) == seen.contains("XMP "))
            require(alpha == (seen.contains("ALPH") || result.alpha))
        }
        return result.copy(alpha=result.alpha || alpha,orientation=orientation)
    }

    private fun exif(b: ByteArray): Int {
        require(b.size >= 8)
        val little = text(b,0,2) == "II"
        require(little || text(b,0,2) == "MM")
        fun short(p: Int): Int { require(p >= 0 && p+2 <= b.size); return if(little) u(b,p) or (u(b,p+1) shl 8) else (u(b,p) shl 8) or u(b,p+1) }
        fun int(p: Int): Int { require(p >= 0 && p+4 <= b.size); return if(little) le(b,p) else be(b,p) }
        require(short(2) == 42)
        val offset = int(4); require(offset >= 8 && offset <= b.size-2)
        val count = short(offset); require(count <= (b.size-offset-2)/12)
        var orientation = 1; var found = false
        repeat(count) {
            val p = offset+2+it*12
            if(short(p) == 274) {
                require(!found && short(p+2) == 3 && int(p+4) == 1)
                orientation = short(p+8); require(orientation in 1..8); found = true
            }
        }
        return orientation
    }
    private fun u(b: ByteArray,p: Int) = b[p].toInt() and 255
    private fun be(b: ByteArray,p: Int) = (u(b,p) shl 24) or (u(b,p+1) shl 16) or (u(b,p+2) shl 8) or u(b,p+3)
    private fun le(b: ByteArray,p: Int) = u(b,p) or (u(b,p+1) shl 8) or (u(b,p+2) shl 16) or (u(b,p+3) shl 24)
    private fun le24(b: ByteArray,p: Int) = u(b,p) or (u(b,p+1) shl 8) or (u(b,p+2) shl 16)
    private fun text(b: ByteArray,p: Int,n: Int) = String(b,p,n,Charsets.ISO_8859_1)
}
