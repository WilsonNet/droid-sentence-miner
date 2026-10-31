package io.github.wilsonnet.droidsentenceminer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `newer releases are offered, numerically`() {
        assertTrue(AppVersion.isNewer("0.4.0", "0.4.1"))
        assertTrue(AppVersion.isNewer("0.4.0", "0.5.0"))
        assertTrue(AppVersion.isNewer("0.9.0", "0.10.0"))
        assertTrue(AppVersion.isNewer("0.4.0", "v1.0"))
    }

    @Test
    fun `same or older releases are not`() {
        assertFalse(AppVersion.isNewer("0.4.1", "0.4.1"))
        assertFalse(AppVersion.isNewer("0.5.0", "0.4.9"))
        assertFalse(AppVersion.isNewer("0.10.0", "0.9.0"))
        assertEquals(0, AppVersion.compare("0.4", "0.4.0"))
    }

    @Test
    fun `a pre-release sorts before its release`() {
        assertTrue(AppVersion.isNewer("0.5.0-beta", "0.5.0"))
        assertFalse(AppVersion.isNewer("0.5.0", "0.5.0-beta"))
        assertTrue(AppVersion.isNewer("0.0.0-test", "0.4.0"))
    }
}
