package com.mackseoul.vcardnative.platform.storage

import android.icu.text.IDNA
import com.mackseoul.vcardnative.domain.profile.HostValidator
import java.text.Normalizer
import java.util.Locale

object AndroidHostValidator : HostValidator {
    private val idna = IDNA.getUTS46Instance(
        IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.CHECK_CONTEXTO or
            IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.NONTRANSITIONAL_TO_UNICODE
    )

    override fun normalize(host: String): String {
        require(host.isNotEmpty() && !host.endsWith('.') && host.none {
            it.isWhitespace() || Character.isSpaceChar(it) || it.code < 32 || it.code == 127
        })
        val input = Normalizer.normalize(host, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val unicodeInfo = IDNA.Info()
        val unicode = idna.nameToUnicode(input, StringBuilder(), unicodeInfo).toString()
        require(!unicodeInfo.hasErrors())
        // UTS46 has compatibility mappings beyond IDNA2008. Accept case/NFC normalization,
        // but reject mappings such as fullwidth characters, ignored characters and dot variants.
        val inputLabels = input.split('.')
        val unicodeLabels = unicode.split('.')
        require(inputLabels.size == unicodeLabels.size)
        require(inputLabels.zip(unicodeLabels).all { (source, decoded) ->
            source.startsWith("xn--") || source == decoded
        })
        val info = IDNA.Info()
        val ascii = idna.nameToASCII(input, StringBuilder(), info).toString().lowercase(Locale.ROOT)
        require(!info.hasErrors() && ascii.length in 1..253 && ascii.all { it.code < 128 })
        require(ascii.split('.').all { it.length in 1..63 })
        if (ascii.all { it in '0'..'9' || it == '.' }) {
            val labels = ascii.split('.')
            require(labels.size == 4 && labels.all {
                it.length <= 3 && (it == "0" || !it.startsWith('0')) && it.toInt() <= 255
            })
        }
        return ascii
    }
}
