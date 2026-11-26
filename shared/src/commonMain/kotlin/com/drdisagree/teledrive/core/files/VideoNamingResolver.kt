package com.drdisagree.teledrive.core.files

data class VideoNamingInput(
    val messageId: Long,
    val rawFileName: String,
    val caption: String,
    val mimeType: String
)

data class ResolvedVideoName(
    val messageId: Long,
    val fileName: String,
    val tags: List<String>
)

data class ResolvedAlbumNames(
    val albumFolderName: String,
    val items: List<ResolvedVideoName>
)

/**
 * Resolves video file names, album folder names, and hashtags using a 4-tier priority:
 * meaningful original filename -> #hashtags -> clean first line of caption -> placeholder.
 */
object VideoNamingResolver {

    private val urlRegex = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val hashtagRegex =
        Regex("#([^\\s#@,，。！？、；：（）()\\[\\]【】《》“”‘’\"'<>|\\\\/`]+)")
    private val onlyDigitsAndSeparators = Regex("^[0-9_\\-.\\s()+]+$")
    private val hexOnly = Regex("^[0-9a-fA-F]{12,}$")
    private val trailingNumericOrHexSuffix =
        Regex("(?:[-_.\\s()]+(?:[0-9]+|[0-9a-fA-F]{8,})|[0-9]+)+$")
    private val trailingEpisodeSuffix =
        Regex("(?:[-_.\\s()]*?(?:ep|episode|part|p)?\\d+|第\\d+[集话回期章篇])+$", RegexOption.IGNORE_CASE)

    private val genericStems = setOf(
        "img", "vid", "mov", "pxl", "dsc", "mvi",
        "video", "animation", "animate", "clip", "movie",
        "file", "document", "doc", "temp", "tmp", "output", "out",
        "download", "dl", "unnamed", "untitled", "tg", "telegram",
        "screenrecording", "screen_recording", "screen-recording", "screenrecord",
        "rp_replay", "rp-replay", "rpreplay", "trim",
        "qq图片", "qq视频", "mmexport", "wx_camera", "wx-camera", "lv", "jianying", "capcut"
    )

    fun extractTags(caption: String): List<String> {
        if (caption.isBlank()) return emptyList()
        val withoutUrls = urlRegex.replace(caption, " ")
        val seen = HashSet<String>()
        val tags = mutableListOf<String>()
        for (match in hashtagRegex.findAll(withoutUrls)) {
            val rawTag = match.groupValues[1].trim()
            if (rawTag.isEmpty() || rawTag.none { it.isLetterOrDigit() }) continue
            val tag = "#$rawTag"
            if (seen.add(tag.lowercase())) {
                tags += tag
            }
        }
        return tags
    }

    fun isMeaningfulVideoName(rawFileName: String): Boolean {
        val base = FileNameUtils.baseNameOf(rawFileName.trim()).trim()
        if (base.isEmpty()) return false
        if (onlyDigitsAndSeparators.matches(base)) return false

        val compactHex = base.replace("-", "").replace("_", "").replace(" ", "")
        if (hexOnly.matches(compactHex)) return false

        val stripped = trailingNumericOrHexSuffix.replace(base, "")
            .trim('-', '_', '.', ' ', '(', ')')
            .lowercase()
        if (stripped.isEmpty()) return false
        if (stripped in genericStems) return false
        if (genericStems.any { stem ->
                stripped.startsWith(stem) && stripped.length > stem.length &&
                    (stripped[stem.length].isDigit() || stripped[stem.length] in charArrayOf('_', '-', ' '))
            } && stripped.substringAfter('_').let { it.isEmpty() || onlyDigitsAndSeparators.matches(it) }
        ) {
            return false
        }
        return true
    }

    fun extractCleanTextTitle(caption: String): String? {
        if (caption.isBlank()) return null
        val stripped = hashtagRegex.replace(urlRegex.replace(caption, " "), " ")
        for (line in stripped.lineSequence()) {
            if (line.none { it.isLetterOrDigit() }) continue
            val cleaned = cleanLine(line)
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetterOrDigit() }) {
                val capped = cleaned.take(MAX_TEXT_TITLE_LENGTH).trim()
                if (capped.isNotEmpty()) {
                    return FileNameUtils.sanitize(capped)
                }
            }
        }
        return null
    }

    fun resolveSingle(input: VideoNamingInput): ResolvedVideoName {
        val tags = extractTags(input.caption)
        return resolveWithTags(input, tags)
    }

    fun resolveAlbum(mediaAlbumId: Long, items: List<VideoNamingInput>): ResolvedAlbumNames {
        if (items.isEmpty()) {
            return ResolvedAlbumNames(
                albumFolderName = "album_$mediaAlbumId",
                items = emptyList()
            )
        }

        val sharedTags = deduplicateTags(items.flatMap { extractTags(it.caption) })
        val sharedCaption = items.firstOrNull { extractCleanTextTitle(it.caption) != null }?.caption
            ?: items.firstOrNull { it.caption.isNotBlank() }?.caption.orEmpty()
        val sharedText = extractCleanTextTitle(sharedCaption)

        val folderName = resolveAlbumFolderName(
            mediaAlbumId = mediaAlbumId,
            items = items,
            sharedTags = sharedTags,
            sharedText = sharedText
        )

        val rawResolved = items.map { item ->
            val effectiveCaption = item.caption.ifBlank { sharedCaption }
            val effectiveTags = extractTags(item.caption).ifEmpty { sharedTags }
            val adjustedInput = if (item.caption.isBlank() && effectiveCaption.isNotBlank()) {
                item.copy(caption = effectiveCaption)
            } else {
                item
            }
            resolveWithTags(adjustedInput, effectiveTags)
        }

        val finalItems = disambiguateAlbumItems(rawResolved)
        return ResolvedAlbumNames(
            albumFolderName = folderName,
            items = finalItems
        )
    }

    private fun resolveWithTags(input: VideoNamingInput, tags: List<String>): ResolvedVideoName {
        val ext = resolveExtension(input.rawFileName, input.mimeType)
        val meaningfulBase = if (isMeaningfulVideoName(input.rawFileName)) {
            FileNameUtils.baseNameOf(input.rawFileName.trim()).trim()
        } else {
            null
        }
        val baseName = when {
            meaningfulBase != null -> appendTagsSuffixIfNeeded(meaningfulBase, tags)
            tags.isNotEmpty() -> formatTags(tags, MAX_TAG_TITLE_LENGTH)
            else -> extractCleanTextTitle(input.caption) ?: "video_${input.messageId}"
        }
        val cappedBase = FileNameUtils.sanitize(baseName).take(MAX_BASE_NAME_LENGTH).trim()
            .ifEmpty { "video_${input.messageId}" }
        val fileName = FileNameUtils.sanitize(
            if (ext.isEmpty()) cappedBase else "$cappedBase.$ext"
        )
        return ResolvedVideoName(
            messageId = input.messageId,
            fileName = fileName,
            tags = tags
        )
    }

    private fun resolveAlbumFolderName(
        mediaAlbumId: Long,
        items: List<VideoNamingInput>,
        sharedTags: List<String>,
        sharedText: String?
    ): String {
        val meaningfulBases = items
            .filter { isMeaningfulVideoName(it.rawFileName) }
            .map { FileNameUtils.baseNameOf(it.rawFileName.trim()).trim() }
            .filter { it.isNotEmpty() }

        val tier1Base = if (meaningfulBases.isNotEmpty()) {
            commonStemOf(meaningfulBases) ?: meaningfulBases.first()
        } else {
            null
        }

        val rawFolder = when {
            tier1Base != null -> appendTagsSuffixIfNeeded(tier1Base, sharedTags)
            sharedTags.isNotEmpty() -> formatTags(sharedTags, MAX_TAG_TITLE_LENGTH)
            sharedText != null -> sharedText
            else -> "album_$mediaAlbumId"
        }
        return FileNameUtils.sanitize(rawFolder).take(MAX_FOLDER_NAME_LENGTH).trim()
            .ifEmpty { "album_$mediaAlbumId" }
    }

    private fun commonStemOf(bases: List<String>): String? {
        if (bases.size < 2) return bases.firstOrNull()
        val stripped = bases.map { base ->
            trailingEpisodeSuffix.replace(base, "")
                .trim('-', '_', '.', ' ', '[', ']', '(', ')')
                .ifEmpty { base }
        }
        var prefix = stripped.first()
        for (other in stripped.drop(1)) {
            prefix = prefix.commonPrefixWith(other, ignoreCase = true)
            if (prefix.isEmpty()) break
        }
        val cleaned = prefix.trim('-', '_', '.', ' ', '[', ']', '(', ')')
        return cleaned.takeIf { it.length >= 2 }
    }

    private fun disambiguateAlbumItems(items: List<ResolvedVideoName>): List<ResolvedVideoName> {
        val totals = items.groupingBy { it.fileName.lowercase() }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return items.map { item ->
            val key = item.fileName.lowercase()
            if ((totals[key] ?: 0) <= 1) {
                item
            } else {
                val index = (seen[key] ?: 0) + 1
                seen[key] = index
                val suffix = index.toString().padStart(2, '0')
                val base = FileNameUtils.baseNameOf(item.fileName)
                val ext = FileNameUtils.extensionOf(item.fileName)
                val numbered = if (ext.isEmpty()) "${base}_$suffix" else "${base}_$suffix.$ext"
                item.copy(fileName = FileNameUtils.sanitize(numbered))
            }
        }
    }

    private fun appendTagsSuffixIfNeeded(base: String, tags: List<String>): String {
        if (tags.isEmpty()) return base
        val formattedTags = formatTags(tags, MAX_TAG_SUFFIX_LENGTH)
        val missingTags = tags.any { tag -> !base.contains(tag, ignoreCase = true) }
        return if (missingTags && formattedTags.isNotEmpty()) {
            "$base [$formattedTags]"
        } else {
            base
        }
    }

    private fun formatTags(tags: List<String>, maxLength: Int): String {
        if (tags.isEmpty()) return ""
        val builder = StringBuilder()
        for (tag in tags) {
            if (builder.isEmpty()) {
                builder.append(tag.take(maxLength))
            } else if (builder.length + 1 + tag.length <= maxLength) {
                builder.append(' ').append(tag)
            } else {
                break
            }
        }
        return builder.toString()
    }

    private fun resolveExtension(rawFileName: String, mimeType: String): String {
        val fromName = FileNameUtils.extensionOf(rawFileName.trim()).lowercase()
        if (fromName.isNotEmpty() && fromName.length <= 8 && fromName.all { it.isLetterOrDigit() }) {
            return fromName
        }
        return when (mimeType.lowercase().substringBefore(';').trim()) {
            "video/mp4" -> "mp4"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/x-flv" -> "flv"
            "video/x-ms-wmv" -> "wmv"
            "video/mp2t" -> "ts"
            "video/3gpp" -> "3gp"
            "video/mpeg" -> "mpg"
            else -> "mp4"
        }
    }

    private fun cleanLine(line: String): String {
        val noEmoji = line.filterNot { ch ->
            ch.isSurrogate() ||
                ch.category == CharCategory.OTHER_SYMBOL ||
                ch == '\u200D' ||
                ch == '\uFE0F'
        }
        val trimmed = noEmoji.trim()
            .dropWhile { ch ->
                !ch.isLetterOrDigit() && ch !in charArrayOf('(', '（', '[', '【', '《', '「', '『')
            }
            .dropLastWhile { ch ->
                !ch.isLetterOrDigit() && ch !in charArrayOf(')', '）', ']', '】', '》', '」', '』')
            }
        return trimmed.replace(Regex("\\s+"), " ").trim()
    }

    private fun deduplicateTags(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (tag in tags) {
            if (seen.add(tag.lowercase())) result += tag
        }
        return result
    }

    private const val MAX_TEXT_TITLE_LENGTH = 40
    private const val MAX_TAG_SUFFIX_LENGTH = 50
    private const val MAX_TAG_TITLE_LENGTH = 60
    private const val MAX_BASE_NAME_LENGTH = 180
    private const val MAX_FOLDER_NAME_LENGTH = 80
}
