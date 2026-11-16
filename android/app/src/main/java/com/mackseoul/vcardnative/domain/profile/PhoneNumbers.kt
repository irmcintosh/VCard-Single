package com.mackseoul.vcardnative.domain.profile

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import java.text.Normalizer
import java.util.Locale

data class PhoneRegion(val isoRegion: String, val countryName: String, val callingCode: String) {
    val selectorLabel: String get() = "$countryName (+$callingCode)"
}

data class ParsedPhoneNumber(
    val e164: String,
    val region: String,
    val international: String,
    val national: String,
)

data class PhoneExportValue(val value: String, val recognized: Boolean)

object PhoneNumbers {
    private val utility: PhoneNumberUtil by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PhoneNumberUtil.getInstance()
    }

    fun defaultRegion(locale: Locale = Locale.getDefault()): String {
        val region = locale.country.uppercase(Locale.ROOT)
        if (isSupportedRegion(region)) return region
        return "US".takeIf(::isSupportedRegion) ?: utility.getSupportedRegions().sorted().first()
    }

    fun isSupportedRegion(region: String): Boolean =
        region.length == 2 && region.all { it in 'A'..'Z' } && region in utility.getSupportedRegions() &&
            utility.getCountryCodeForRegion(region) > 0

    fun regions(locale: Locale = Locale.getDefault()): List<PhoneRegion> =
        utility.getSupportedRegions().mapNotNull { regionInfo(it, locale) }
            .sortedWith(compareBy<PhoneRegion> { it.countryName.lowercase(locale) }.thenBy { it.isoRegion })

    fun region(region: String, locale: Locale = Locale.getDefault()): PhoneRegion? =
        regionInfo(region, locale)

    // Every whitespace-separated term must match a name, ISO code, or calling code; input order is kept.
    fun filterRegions(regions: List<PhoneRegion>, query: String): List<PhoneRegion> {
        val terms = searchFold(query.replace('+', ' ')).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return regions
        return regions.filter { region ->
            val name = searchFold(region.countryName)
            val iso = region.isoRegion.lowercase(Locale.ROOT)
            terms.all { term ->
                if (term.all { it in '0'..'9' }) region.callingCode.startsWith(term)
                else name.contains(term) || iso.startsWith(term)
            }
        }
    }

    private fun searchFold(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.ROOT)

    fun regionForNumber(value: String, fallbackRegion: String): String =
        parse(value, fallbackRegion)?.region?.takeIf(::isSupportedRegion) ?: fallbackRegion

    fun parse(value: String, region: String): ParsedPhoneNumber? {
        if (!isSupportedRegion(region) || value.isBlank() ||
            !value.all { it in "0123456789 +-.()" } ||
            value.drop(1).any { it == '+' }) return null
        return try {
            val parsed = utility.parse(value, region)
            if (parsed.hasExtension() || !utility.isValidNumber(parsed)) return null
            val resolvedRegion = utility.getRegionCodeForNumber(parsed)
                .takeIf(::isSupportedRegion) ?: region
            ParsedPhoneNumber(
                utility.format(parsed, PhoneNumberFormat.E164),
                resolvedRegion,
                utility.format(parsed, PhoneNumberFormat.INTERNATIONAL),
                utility.format(parsed, PhoneNumberFormat.NATIONAL),
            )
        } catch (_: NumberParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun exportValue(
        value: String,
        region: String,
        includeCountryCode: Boolean,
        storedE164: String? = null,
    ): PhoneExportValue {
        val parsed = storedE164?.let { parse(it, region)?.takeIf { result -> result.e164 == it } }
            ?: parse(value, region)
        if (parsed != null) {
            return PhoneExportValue(
                if (includeCountryCode) parsed.international else parsed.national,
                recognized = true,
            )
        }
        return PhoneExportValue(value.filter { it in '0'..'9' || it == '+' }, recognized = false)
    }

    private fun regionInfo(region: String, locale: Locale): PhoneRegion? {
        if (!isSupportedRegion(region)) return null
        val name = Locale.Builder().setRegion(region).build().getDisplayCountry(locale)
        val code = utility.getCountryCodeForRegion(region)
        return if (name.isBlank()) null else PhoneRegion(region, name, code.toString())
    }
}
