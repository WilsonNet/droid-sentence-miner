package io.github.wilsonnet.droidsentenceminer.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class CardImageSizeTest {
    @Test fun scalesDownKeepingAspect() {
        assertEquals(821 to 462, CardImageSize.fit(1280, 720, 462))
        assertEquals(616 to 462, CardImageSize.fit(1280, 960, 462))
    }

    @Test fun neverUpscales() {
        assertEquals(640 to 360, CardImageSize.fit(640, 360, 462))
        assertEquals(800 to 462, CardImageSize.fit(800, 462, 462))
    }

    @Test fun zeroMeansOriginal() {
        assertEquals(1280 to 720, CardImageSize.fit(1280, 720, 0))
    }
}
