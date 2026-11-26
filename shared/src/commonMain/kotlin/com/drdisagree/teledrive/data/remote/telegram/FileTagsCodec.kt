package com.drdisagree.teledrive.data.remote.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object FileTagsCodec {

    private const val KEY_TAGS = "tags"

    fun readTags(manifestExtraText: String?): List<String> =
        readTags(ExtraFields.fromText(manifestExtraText))

    fun readTags(extra: Map<String, JsonElement>): List<String> {
        val array = extra[KEY_TAGS] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun writeTags(
        extra: Map<String, JsonElement>,
        tags: List<String>
    ): Map<String, JsonElement> {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return extra - KEY_TAGS
        return extra + (KEY_TAGS to JsonArray(normalized.map(::JsonPrimitive)))
    }

    fun writeTagsText(manifestExtraText: String?, tags: List<String>): String? =
        ExtraFields.toText(writeTags(ExtraFields.fromText(manifestExtraText), tags))
}
