package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseDeinflectorTest {

    private fun terms(source: String): List<String> =
        JapaneseDeinflector.deinflect(source).map { it.term }

    @Test
    fun `teiru deinflects to dictionary form`() {
        assertTrue("食べる" in terms("食べている"))
    }

    @Test
    fun `negative polite past deinflects`() {
        assertTrue("食べる" in terms("食べませんでした"))
    }

    @Test
    fun `plain negative deinflects`() {
        assertTrue("行く" in terms("行かない"))
    }

    @Test
    fun `potential form deinflects`() {
        assertTrue("食べる" in terms("食べられる"))
        assertTrue("書く" in terms("書ける"))
    }

    @Test
    fun `passive godan deinflects`() {
        assertTrue("書く" in terms("書かれる"))
    }

    @Test
    fun `masu stem deinflects`() {
        assertTrue("食べる" in terms("食べます"))
        assertTrue("読む" in terms("読みます"))
    }

    @Test
    fun `te form deinflects`() {
        assertTrue("読む" in terms("読んで"))
        assertTrue("書く" in terms("書いて"))
        assertTrue("話す" in terms("話して"))
    }

    @Test
    fun `ta form deinflects`() {
        assertTrue("行く" in terms("行った"))
        assertTrue("読む" in terms("読んだ"))
    }

    @Test
    fun `i adjective forms deinflect`() {
        assertTrue("楽しい" in terms("楽しく"))
        assertTrue("楽しい" in terms("楽しかった"))
        assertTrue("楽しい" in terms("楽しくない"))
    }

    @Test
    fun `suru and kuru verbs deinflect`() {
        assertTrue("する" in terms("された"))
        assertTrue("する" in terms("している"))
        assertTrue("来る" in terms("来た"))
        assertTrue("来る" in terms("来ます"))
    }

    @Test
    fun `imperative and volitional deinflect`() {
        assertTrue("食べる" in terms("食べろ"))
        assertTrue("食べる" in terms("食べよう"))
        assertTrue("する" in terms("しろ"))
    }

    @Test
    fun `tai form deinflects`() {
        assertTrue("食べる" in terms("食べたい"))
    }

    @Test
    fun `provisional conditional deinflects`() {
        assertTrue("行く" in terms("行けば"))
    }

    @Test
    fun `deinflection reasons are recorded`() {
        val candidate = JapaneseDeinflector.deinflect("食べられなかった")
            .firstOrNull { it.term == "食べる" }
        assertEquals("食べる", candidate?.term ?: "")
        assertTrue((candidate?.reasons?.size ?: 0) > 0)
    }

    @Test
    fun `dictionary form is always a candidate`() {
        assertTrue("食べる" in terms("食べる"))
    }
}
