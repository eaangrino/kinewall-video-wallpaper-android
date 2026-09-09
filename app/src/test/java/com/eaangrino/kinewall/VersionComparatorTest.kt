package com.eaangrino.kinewall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun newerSemanticVersionIsDetected() {
        assertTrue(VersionComparator.isNewer("v0.4.0", "0.3.0"))
    }

    @Test
    fun sameOrOlderVersionIsIgnored() {
        assertFalse(VersionComparator.isNewer("0.3.0", "0.3.0"))
        assertFalse(VersionComparator.isNewer("0.2.9", "0.3.0"))
    }

    @Test
    fun missingPatchSegmentIsComparedNumerically() {
        assertTrue(VersionComparator.isNewer("0.3.1", "0.3"))
        assertFalse(VersionComparator.isNewer("0.3", "0.3.0"))
    }

    @Test
    fun uppercasePrefixAndWhitespaceAreNormalized() {
        assertTrue(VersionComparator.isNewer("  V1.2.0  ", "1.1.9"))
    }

    @Test
    fun multiDigitSegmentsAreComparedNumerically() {
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.9"))
        assertFalse(VersionComparator.isNewer("1.9.9", "1.10.0"))
    }

    @Test
    fun malformedVersionIsIgnored() {
        assertFalse(VersionComparator.isNewer("release-0.4.0", "0.3.0"))
        assertFalse(VersionComparator.isNewer("0.4.0", "release-0.3.0"))
        assertFalse(VersionComparator.isNewer("", "0.3.0"))
    }

    @Test
    fun overflowingNumericSegmentIsIgnored() {
        assertFalse(VersionComparator.isNewer("999999999999.0.0", "1.0.0"))
    }
}
