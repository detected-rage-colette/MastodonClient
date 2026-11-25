package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

@Serializable
data class StatusDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    @SerialName("in_reply_to_account_id") val inReplyToAccountId: String? = null,
    val account: AccountDto,
    val content: String = "",
    @SerialName("spoiler_text") val spoilerText: String = "",
    val sensitive: Boolean = false,
    val visibility: String = "public",
    val language: String? = null,
    val url: String? = null,
    @SerialName("replies_count") val repliesCount: Long = 0,
    @SerialName("reblogs_count") val reblogsCount: Long = 0,
    @SerialName("favourites_count") val favouritesCount: Long = 0,
    val application: StatusApplicationDto? = null,
    val favourited: Boolean = false,
    val reblogged: Boolean = false,
    val bookmarked: Boolean = false,
    val pinned: Boolean? = null,
    @SerialName("emoji_reactions") val emojiReactions: List<EmojiReactionDto>? = null,
    val pleroma: PleromaStatusDto? = null,
    val emojis: List<AccountEmojiDto> = emptyList(),
    val mentions: List<StatusMentionDto> = emptyList(),
    @SerialName("quote_approval") val quoteApproval: QuoteApprovalDto? = null,
    @Serializable(with = StatusQuoteSerializer::class) val quote: StatusQuoteDto? = null,
    val card: PreviewCardDto? = null,
    val poll: PollDto? = null,
    @SerialName("media_attachments") val mediaAttachments: List<MediaAttachmentDto> = emptyList(),
    val reblog: StatusDto? = null,
    val filtered: List<FilterResultDto>? = null,
)

@Serializable
data class PleromaStatusDto(
    @SerialName("emoji_reactions") val emojiReactions: List<EmojiReactionDto>? = null,
)

@Serializable
data class PollDto(
    val id: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val expired: Boolean = false,
    val multiple: Boolean = false,
    @SerialName("votes_count") val votesCount: Long = 0,
    @SerialName("voters_count") val votersCount: Long? = null,
    val voted: Boolean? = null,
    @SerialName("own_votes") val ownVotes: List<Int> = emptyList(),
    val options: List<PollOptionDto> = emptyList(),
    val emojis: List<AccountEmojiDto> = emptyList(),
)

@Serializable
data class PollOptionDto(
    val title: String = "",
    @SerialName("votes_count") val votesCount: Long? = null,
)

@Serializable
data class StatusMentionDto(
    val id: String,
    val username: String = "",
    val acct: String = "",
    val url: String = "",
)

@Serializable
data class QuoteApprovalDto(
    @SerialName("current_user") val currentUser: String? = null,
)

@Serializable
data class StatusQuoteDto(
    val state: String? = null,
    @SerialName("quoted_status") val quotedStatus: StatusDto? = null,
    @SerialName("quoted_status_id") val quotedStatusId: String? = null,
)

/** Normalizes both Mastodon 4.5 Quote wrappers and Fedibird direct Status/ID quote payloads. */
object StatusQuoteSerializer : JsonTransformingSerializer<StatusQuoteDto>(StatusQuoteDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element is JsonPrimitive) {
            val id = element.contentOrNull?.takeIf(String::isNotBlank) ?: return element
            return buildJsonObject {
                put("state", JsonPrimitive("accepted"))
                put("quoted_status_id", JsonPrimitive(id))
            }
        }
        val obj = element as? JsonObject ?: return element
        if ("state" in obj || "quoted_status" in obj || "quoted_status_id" in obj) return obj
        if ("id" in obj && "account" in obj) {
            val quotedId = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val muted = (obj["quote_muted"] as? JsonPrimitive)?.booleanOrNull == true
            return buildJsonObject {
                put("state", JsonPrimitive(if (muted) "muted_account" else "accepted"))
                if (!muted) put("quoted_status", obj)
                quotedId?.let { put("quoted_status_id", JsonPrimitive(it)) }
            }
        }
        return obj
    }
}

@Serializable
data class StatusApplicationDto(
    val name: String,
    val website: String? = null,
)

@Serializable
data class EmojiReactionDto(
    val name: String,
    val count: Long = 0,
    val me: Boolean = false,
    val url: String? = null,
    @SerialName("static_url") val staticUrl: String? = null,
    @SerialName("account_ids") val accountIds: List<String> = emptyList(),
    val domain: String? = null,
)

@Serializable
data class StatusContextDto(
    val ancestors: List<StatusDto> = emptyList(),
    val descendants: List<StatusDto> = emptyList(),
)

@Serializable
data class PreviewCardDto(
    val url: String,
    val title: String = "",
    val description: String = "",
    val type: String = "link",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("provider_name") val providerName: String = "",
    val image: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class MediaAttachmentDto(
    val id: String,
    val type: String,
    val url: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val description: String? = null,
    val meta: MediaMetadataDto? = null,
)

@Serializable
data class MediaMetadataDto(
    val original: MediaDimensionsDto? = null,
    val small: MediaDimensionsDto? = null,
    val width: Int? = null,
    val height: Int? = null,
    val aspect: Float? = null,
)

@Serializable
data class MediaDimensionsDto(
    val width: Int? = null,
    val height: Int? = null,
    val aspect: Float? = null,
)

@Serializable
data class CustomEmojiDto(
    val shortcode: String,
    val url: String,
    @SerialName("static_url") val staticUrl: String,
    val category: String? = null,
)

@Serializable
data class StatusSourceDto(
    val id: String,
    val text: String = "",
    @SerialName("spoiler_text") val spoilerText: String = "",
)
