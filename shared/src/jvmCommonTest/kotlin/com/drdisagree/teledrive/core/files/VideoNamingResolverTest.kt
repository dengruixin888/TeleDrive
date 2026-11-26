package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.data.remote.telegram.FileTagsCodec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNamingResolverTest {

    @Test
    fun `extractTags parses screenshot hashtags and skips url fragments`() {
        val caption = """
            #小姨妈 #陆雪琪 #AI短视频 #3D国漫
            https://example.com/watch?v=1#timestamp
            ⭐作品来源于国漫精品社区 #3d国漫
        """.trimIndent()

        val tags = VideoNamingResolver.extractTags(caption)

        assertEquals(
            listOf("#小姨妈", "#陆雪琪", "#AI短视频", "#3D国漫"),
            tags
        )
    }

    @Test
    fun `isMeaningfulVideoName distinguishes real titles from generic names`() {
        assertTrue(VideoNamingResolver.isMeaningfulVideoName("凡人修仙传01.mp4"))
        assertTrue(VideoNamingResolver.isMeaningfulVideoName("documentary_earth.mp4"))
        assertTrue(VideoNamingResolver.isMeaningfulVideoName("Episode 1 - Intro.mkv"))

        assertFalse(VideoNamingResolver.isMeaningfulVideoName(""))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("IMG_1234.MP4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("VID20260501123456.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("173123456.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("video_55.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("animation_12.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("document.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("mmexport1712345678.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("PXL_20260501_120000.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("a1b2c3d4e5f60718.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("screen_recording_2026.mp4"))
        assertFalse(VideoNamingResolver.isMeaningfulVideoName("QQ视频_123456.mp4"))
    }

    @Test
    fun `resolveSingle appends tags to meaningful file name without duplicating existing tags`() {
        val resolved = VideoNamingResolver.resolveSingle(
            VideoNamingInput(
                messageId = 10L,
                rawFileName = "斗破苍穹.mkv",
                caption = "#玄幻 #3D国漫\n精彩片段",
                mimeType = "video/x-matroska"
            )
        )
        assertEquals("斗破苍穹 [#玄幻 #3D国漫].mkv", resolved.fileName)
        assertEquals(listOf("#玄幻", "#3D国漫"), resolved.tags)

        val alreadyTagged = VideoNamingResolver.resolveSingle(
            VideoNamingInput(
                messageId = 11L,
                rawFileName = "斗破苍穹 #3D国漫.mp4",
                caption = "#3D国漫",
                mimeType = "video/mp4"
            )
        )
        assertEquals("斗破苍穹 #3D国漫.mp4", alreadyTagged.fileName)
    }

    @Test
    fun `resolveSingle falls back from tags to clean caption line to placeholder`() {
        val fromTags = VideoNamingResolver.resolveSingle(
            VideoNamingInput(
                messageId = 20L,
                rawFileName = "VID_20260501.mp4",
                caption = "#小姨妈 #陆雪琪 #AI短视频 #3D国漫\n⭐作品来源于国漫精品社区",
                mimeType = "video/mp4"
            )
        )
        assertEquals("#小姨妈 #陆雪琪 #AI短视频 #3D国漫.mp4", fromTags.fileName)
        assertEquals(listOf("#小姨妈", "#陆雪琪", "#AI短视频", "#3D国漫"), fromTags.tags)

        val fromBody = VideoNamingResolver.resolveSingle(
            VideoNamingInput(
                messageId = 21L,
                rawFileName = "video_21.mp4",
                caption = "⭐作品来源于国漫精品社区（特别篇） https://t.me/demo\n第二行简介",
                mimeType = "video/mp4"
            )
        )
        assertEquals("作品来源于国漫精品社区（特别篇）.mp4", fromBody.fileName)
        assertTrue(fromBody.tags.isEmpty())

        val placeholder = VideoNamingResolver.resolveSingle(
            VideoNamingInput(
                messageId = 22L,
                rawFileName = "",
                caption = "",
                mimeType = "video/x-matroska"
            )
        )
        assertEquals("video_22.mkv", placeholder.fileName)
    }

    @Test
    fun `resolveAlbum shares tags across album items and disambiguates identical names`() {
        val album = VideoNamingResolver.resolveAlbum(
            mediaAlbumId = 999L,
            items = listOf(
                VideoNamingInput(
                    messageId = 101L,
                    rawFileName = "document.mp4",
                    caption = "#小姨妈 #陆雪琪 #AI短视频 #3D国漫\n⭐作品来源于国漫精品社区",
                    mimeType = "video/mp4"
                ),
                VideoNamingInput(
                    messageId = 102L,
                    rawFileName = "document.mp4",
                    caption = "",
                    mimeType = "video/mp4"
                )
            )
        )

        assertEquals("#小姨妈 #陆雪琪 #AI短视频 #3D国漫", album.albumFolderName)
        assertEquals(2, album.items.size)
        assertEquals("#小姨妈 #陆雪琪 #AI短视频 #3D国漫_01.mp4", album.items[0].fileName)
        assertEquals("#小姨妈 #陆雪琪 #AI短视频 #3D国漫_02.mp4", album.items[1].fileName)
        assertEquals(listOf("#小姨妈", "#陆雪琪", "#AI短视频", "#3D国漫"), album.items[1].tags)
    }

    @Test
    fun `resolveAlbum extracts common stem for multi episode folder`() {
        val album = VideoNamingResolver.resolveAlbum(
            mediaAlbumId = 888L,
            items = listOf(
                VideoNamingInput(201L, "凡人修仙传 第01集.mp4", "#国漫", "video/mp4"),
                VideoNamingInput(202L, "凡人修仙传 第02集.mp4", "", "video/mp4")
            )
        )

        assertEquals("凡人修仙传 [#国漫]", album.albumFolderName)
        assertEquals("凡人修仙传 第01集 [#国漫].mp4", album.items[0].fileName)
        assertEquals("凡人修仙传 第02集 [#国漫].mp4", album.items[1].fileName)
    }

    @Test
    fun `extractCleanTextTitle returns null for punctuation only captions`() {
        assertNull(VideoNamingResolver.extractCleanTextTitle("🔥✨ #tag1 https://example.com"))
    }

    @Test
    fun `FileTagsCodec roundtrips tags while keeping unrelated extra fields`() {
        val existing = mapOf("author" to JsonPrimitive("studio"))
        val updated = FileTagsCodec.writeTags(existing, listOf(" #国漫 ", "", "#3D "))
        assertEquals(listOf("#国漫", "#3D"), FileTagsCodec.readTags(updated))
        assertEquals(JsonPrimitive("studio"), updated["author"])

        val cleared = FileTagsCodec.writeTagsText("""{"author":"studio","tags":["#a"]}""", emptyList())
        assertEquals("""{"author":"studio"}""", cleared)
    }
}
