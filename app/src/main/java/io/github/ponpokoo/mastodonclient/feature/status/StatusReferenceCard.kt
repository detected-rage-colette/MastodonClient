package io.github.ponpokoo.mastodonclient.feature.status

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.mentionedAccountIdFor
import io.github.ponpokoo.mastodonclient.feature.common.ContentWarning
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.FilterWarning
import io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel
import io.github.ponpokoo.mastodonclient.feature.common.toShape

@Composable
internal fun StatusReferenceCard(
    status: TimelineStatus,
    referenceKey: String,
    displayPreferences: TimelineDisplayPreferences,
    collapseLongContent: Boolean,
    onStatusClick: ((String) -> Unit)?,
    onAuthorClick: ((String) -> Unit)?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status.filterDisplay.hidden) return
    var filterRevealed by rememberSaveable(
        status.filterScope,
        referenceKey,
        status.statusId,
        status.filterDisplay.warnings,
    ) { mutableStateOf(false) }
    val reading = LocalReadingSettings.current
    var cwOverride by rememberSaveable(
        reading.accountKey,
        status.filterScope,
        referenceKey,
        status.statusId,
        status.spoilerText,
    ) { mutableStateOf<Boolean?>(null) }
    val cwRevealed = cwOverride ?: (status.spoilerText.isBlank() || reading.preferences.expandSpoilers)
    val visible = status.filterDisplay.warnings.isEmpty() || filterRevealed
    val hasDetails = status.filterDisplay.warnings.isNotEmpty() ||
        status.spoilerText.isNotBlank() ||
        status.contentHtml.isNotBlank()

    Surface(
        onClick = { if (visible) onStatusClick?.invoke(status.statusId) },
        enabled = visible && onStatusClick != null,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = accountAvatarModel(status.author.avatarUrl),
                    contentDescription = "${status.author.displayName}のプロフィール画像",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(displayPreferences.avatarIconShape.toShape())
                        .then(
                            if (onAuthorClick == null) Modifier
                            else Modifier.clickable { onAuthorClick(status.author.id) },
                        ),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(8.dp))
                CustomEmojiText(
                    text = status.author.displayName,
                    emojis = status.author.customEmojis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasDetails) {
                Spacer(Modifier.height(3.dp))
            }
            if (status.filterDisplay.warnings.isNotEmpty()) {
                FilterWarning(
                    status.filterDisplay.warnings,
                    filterRevealed,
                    { filterRevealed = !filterRevealed },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = displayPreferences.fontSize.spValue(),
                        lineHeight = displayPreferences.lineHeightSp().sp,
                    ),
                )
            }
            if (!visible) return@Column
            if (status.spoilerText.isNotBlank()) {
                ContentWarning(
                    expanded = cwRevealed,
                    onToggle = { cwOverride = !cwRevealed },
                ) {
                    CustomEmojiText(
                        text = status.spoilerText,
                        emojis = status.customEmojis,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = displayPreferences.fontSize.spValue(),
                            lineHeight = displayPreferences.lineHeightSp().sp,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (cwRevealed && status.contentHtml.isNotBlank()) {
                val body: @Composable (Int, ((TextLayoutResult) -> Unit)?) -> Unit = { maxLines, onLayout ->
                    StatusContentText(
                        contentHtml = status.contentHtml,
                        customEmojis = status.customEmojis,
                        emojiHeight = 24.dp,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = displayPreferences.fontSize.spValue(),
                            lineHeight = displayPreferences.lineHeightSp().sp,
                        ),
                        onLinkClick = { link ->
                            val accountId = status.mentionedAccountIdFor(link)
                            if (accountId != null && onAuthorClick != null) onAuthorClick(accountId)
                            else onOpenLink(link)
                        },
                        onNonLinkClick = onStatusClick?.takeIf { visible }?.let { { it(status.statusId) } },
                        maxLines = maxLines,
                        onTextLayout = onLayout,
                    )
                }
                if (collapseLongContent && status.spoilerText.isBlank()) {
                    key(
                        reading.transientSessionKey,
                        status.filterScope,
                        referenceKey,
                        status.statusId,
                        status.contentHtml,
                    ) {
                        CollapsibleStatusBody(
                            accountKey = reading.transientSessionKey,
                            filterScope = status.filterScope,
                            timelineId = referenceKey,
                            statusId = status.statusId,
                            contentHtml = status.contentHtml,
                        ) { maxLines, onLayout -> body(maxLines, onLayout) }
                    }
                } else {
                    body(Int.MAX_VALUE, null)
                }
            }
        }
    }
}

@Composable
internal fun StatusQuotePlaceholder(
    state: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("status_quote_placeholder"),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = quotePlaceholderMessage(state),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun quotePlaceholderMessage(state: String): String = when (state.lowercase()) {
    "pending" -> "引用は承認待ちです"
    "rejected", "revoked" -> "引用は取り消されました"
    "deleted" -> "引用元の投稿は削除されました"
    "blocked_account", "blocked_domain", "muted_account" -> "ミュートまたはブロック中の投稿を引用しています"
    "filtered" -> "フィルターにより非表示の投稿を引用しています"
    else -> "引用を表示できません"
}
