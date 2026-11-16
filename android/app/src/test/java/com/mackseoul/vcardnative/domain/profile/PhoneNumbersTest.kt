package com.mackseoul.vcardnative.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PhoneNumbersTest {
    @Test fun deviceRegionUsesLocaleWithoutLocationData() {
        assertEquals("GB", PhoneNumbers.defaultRegion(Locale.UK))
        assertEquals("JP", PhoneNumbers.defaultRegion(Locale.JAPAN))
        assertTrue(PhoneNumbers.regions(Locale.US).any {
            it.isoRegion == "US" && it.callingCode == "1"
        })
        assertEquals("United States (+1)", PhoneNumbers.region("US", Locale.US)?.selectorLabel)
    }

    @Test fun filtersRegionsByNameCallingCodeAndIsoWithoutChangingOrder() {
        val all = PhoneNumbers.regions(Locale.US)
        fun isos(query: String) = PhoneNumbers.filterRegions(all, query).map { it.isoRegion }

        assertEquals(all, PhoneNumbers.filterRegions(all, "  "))
        assertEquals(all, PhoneNumbers.filterRegions(all, "+"))
        assertTrue("US" in isos("UNITED") && "GB" in isos("united"))
        assertTrue(isos("+44").let { "GB" in it && "US" !in it })
        assertEquals(isos("+44"), isos("44"))
        assertTrue("US" in isos("+1"))
        assertEquals(listOf("KR"), isos("kr").filter { it == "KR" })
        assertTrue("CI" in isos("cote") && "CI" in isos("CÔTE"))
        assertEquals(all.map { it.isoRegion }.filter { it in isos("united") }, isos("united"))
        assertTrue(isos("zzzzzz").isEmpty())
    }

    @Test fun parsesRegionalNationalNumberAndFormatsBothSharingOptions() {
        val parsed = requireNotNull(PhoneNumbers.parse("020 7031 3000", "GB"))
        assertEquals("+442070313000", parsed.e164)
        assertEquals("GB", parsed.region)
        assertEquals("+44 20 7031 3000", parsed.international)
        assertEquals("020 7031 3000", parsed.national)

        assertEquals(
            PhoneExportValue("+44 20 7031 3000", true),
            PhoneNumbers.exportValue("020 7031 3000", "GB", includeCountryCode = true, storedE164 = parsed.e164),
        )
        assertEquals(
            PhoneExportValue("020 7031 3000", true),
            PhoneNumbers.exportValue("020 7031 3000", "GB", includeCountryCode = false, storedE164 = parsed.e164),
        )
    }

    @Test fun rejectsUnsupportedAndInvalidNumbersAndPreservesLegacyExportFallback() {
        assertNull(PhoneNumbers.parse("020 7031 3000", "ZZ"))
        assertNull(PhoneNumbers.parse("12", "US"))
        assertNull(PhoneNumbers.parse("202-555-0101 ext 2", "US"))
        assertFalse(PhoneNumbers.exportValue("123", "US", true).recognized)
        assertEquals("123", PhoneNumbers.exportValue("123", "US", true).value)
    }
}
