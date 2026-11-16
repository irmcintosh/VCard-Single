package com.mackseoul.vcardnative.ui.widget

import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.RevisionToken
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.ImageAsset
import com.mackseoul.vcardnative.domain.profile.ProfileField

sealed interface QuickCardWidgetState {
    data object Empty : QuickCardWidgetState
    data class Unavailable(
        val message: String,
        val recoveryToken: String?,
        val pendingOperation: String?,
    ) : QuickCardWidgetState
    data class Ready(
        val revision: RevisionToken,
        val displayName: String,
        val professionalTitle: String?,
        val photo: ImageAsset?,
        val initials: String?,
        val firstName: String? = null,
        /** Committed saved accent only; editor drafts never reach the widget projection. */
        val accent: Accent = Appearance().accent,
    ) : QuickCardWidgetState {
        val usesGenericPerson: Boolean get() = photo == null && initials == null

        fun identityText(size: QuickCardWidgetSize): String = when (size) {
            QuickCardWidgetSize.VeryNarrow -> initials.orEmpty()
            QuickCardWidgetSize.Compact -> firstName ?: QuickCardWidgetProjection.firstName(displayName).orEmpty()
            QuickCardWidgetSize.Standard, QuickCardWidgetSize.Expanded -> displayName
        }

        /**
         * Text for the name view, or null to hide it. VeryNarrow omits text that would only repeat the initials
         * already drawn in the avatar (no decoded photo); with a photo the initials remain as its label.
         */
        fun visibleIdentityText(size: QuickCardWidgetSize, photoShown: Boolean): String? {
            val text = identityText(size)
            if (text.isBlank()) return null
            return if (size == QuickCardWidgetSize.VeryNarrow && !photoShown && text == initials) null else text
        }
    }
}

enum class QuickCardWidgetSize(
    val targetColumns: Int,
    val targetRows: Int,
    val maxNameLines: Int,
    val maxTitleLines: Int,
    val photoEdgeDp: Int,
) {
    VeryNarrow(2, 1, 1, 1, 24),
    Compact(2, 1, 1, 1, 40),
    Standard(2, 2, 2, 2, 56),
    Expanded(4, 2, 2, 2, 80),
}

object QuickCardWidgetProjection {
    fun from(read: ProfileRead): QuickCardWidgetState = when (read) {
        ProfileRead.Empty -> QuickCardWidgetState.Empty
        is ProfileRead.Unavailable -> QuickCardWidgetState.Unavailable(
            read.message, read.recoveryToken, read.pendingOperation,
        )
        is ProfileRead.Ready -> {
            val data = read.saved.data
            val name = data.value(ProfileField.FullName).orEmpty()
            QuickCardWidgetState.Ready(
                revision = read.saved.token,
                displayName = name,
                professionalTitle = data.value(ProfileField.ProfessionalTitle)?.takeIf {
                    data.profile.visibility[ProfileField.ProfessionalTitle]?.showOnCard == true && it.isNotBlank()
                },
                photo = data.profile.profileImage?.takeIf {
                    data.profile.visibility[ProfileField.ProfileImage]?.showOnCard == true
                },
                initials = initials(name),
                firstName = firstName(name, data.value(ProfileField.FirstName)),
                accent = data.appearance.accent,
            )
        }
    }

    fun firstName(fullName: String, savedFirstName: String? = null): String? {
        val structured = savedFirstName?.trim { it.isWhitespace() || Character.isSpaceChar(it) }
        if (!structured.isNullOrEmpty() && initials(structured) != null) return structured
        val trimmed = fullName.trim { it.isWhitespace() || Character.isSpaceChar(it) }
        if (trimmed.isEmpty() || initials(trimmed) == null) return null
        val separator = trimmed.indexOfFirst { it.isWhitespace() || Character.isSpaceChar(it) }
        return if (separator < 0) trimmed else trimmed.substring(0, separator)
    }

    fun initials(name: String): String? {
        var index = 0
        var inWord = false
        var wordCount = 0
        var first: Int? = null
        var last: Int? = null
        while (index < name.length) {
            val character = name[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= name.length || !name[index + 1].isLowSurrogate()) return null
            } else if (character.isLowSurrogate()) return null
            val codePoint = name.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                inWord = false
            } else if (!inWord) {
                if (Character.isISOControl(codePoint)) return null
                wordCount++
                if (first == null) first = codePoint
                last = codePoint
                inWord = true
            }
        }
        val initial = first ?: return null
        return buildString {
            appendCodePoint(initial)
            if (wordCount > 1) appendCodePoint(requireNotNull(last))
        }
    }

    const val TITLE_INITIALS_MAX_LENGTH = 4

    /**
     * Derived on demand from the stored title and never persisted. Returns null when no reliable abbreviation exists
     * (blank, punctuation-only, or scripts written without word spaces); callers then show the title itself.
     */
    fun titleInitials(title: String, maxLength: Int = TITLE_INITIALS_MAX_LENGTH): String? {
        require(maxLength >= 2)
        var words = titleWords(title)
        if (words.size > 1) {
            val kept = words.filterNot(::isParentheticalAcronym)
            if (kept.isNotEmpty()) words = kept
        }
        if (words.size == 1 && isAcronym(words[0], maxLength)) return words[0]
        val units = ArrayList<String>()
        for (word in words) {
            val at = firstLetterOrDigit(word)
            if (at < 0) continue
            if (Character.UnicodeScript.of(word.codePointAt(at)) in UNSPACED_SCRIPTS) return null
            units += initialUnit(word, at)
        }
        if (units.isEmpty()) return null
        // Over the limit keep the leading words and the final role noun, e.g. SPSE for five words.
        val kept = if (units.size > maxLength) units.take(maxLength - 1) + units.last() else units
        return kept.joinToString("")
    }

    private fun titleWords(title: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < title.length) {
            val codePoint = title.codePointAt(index)
            index += Character.charCount(codePoint)
            val separator = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
                (codePoint < 0x10000 && TITLE_WORD_SEPARATORS.indexOf(codePoint.toChar()) >= 0)
            if (separator) {
                if (current.isNotEmpty()) words += current.toString()
                current.setLength(0)
            } else current.appendCodePoint(codePoint)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    private fun firstLetterOrDigit(word: String): Int {
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) return index
            index += Character.charCount(codePoint)
        }
        return -1
    }

    private fun initialUnit(word: String, at: Int): String = buildString {
        val first = word.codePointAt(at)
        appendCodePoint(Character.toUpperCase(first))
        var index = at + Character.charCount(first)
        while (index < word.length) {
            val mark = word.codePointAt(index)
            if (!isCombiningMark(mark)) break
            appendCodePoint(mark)
            index += Character.charCount(mark)
        }
    }

    private fun isCombiningMark(codePoint: Int): Boolean = Character.getType(codePoint).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
            it == Character.ENCLOSING_MARK.toInt()
    }

    private fun isAcronym(word: String, maxLength: Int): Boolean {
        var count = 0
        var upper = 0
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            index += Character.charCount(codePoint)
            count++
            if (Character.isUpperCase(codePoint)) upper++ else if (!Character.isDigit(codePoint)) return false
        }
        return upper >= 2 && count <= maxLength
    }

    private fun isParentheticalAcronym(word: String): Boolean =
        word.length >= 4 && word.startsWith("(") && word.endsWith(")") &&
            isAcronym(word.substring(1, word.length - 1), Int.MAX_VALUE)

    private const val TITLE_WORD_SEPARATORS = "-\u2010\u2011\u2012\u2013\u2014\u2015/\\"
    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )
}