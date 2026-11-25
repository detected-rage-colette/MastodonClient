package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TimelineModerationCacheTest {
    @Test fun loadingAndNoticeUpdatesReuseRowsWhileUpdatingUnreadCounts() {
        val cache = TimelineModerationCache()
        val source = TimelineUiState(statuses = listOf(testStatus()), unseenStreamIds = setOf("post", "missing"),
            unseenRefreshIds = setOf("post", "hidden"))
        val first = cache.apply(source, AccountModerationState(), testAccount)
        val next = cache.apply(source.copy(isLoadingMore = true, unseenStreamIds = emptySet()), AccountModerationState(), testAccount)
        assertSame(first.statuses, next.statuses)
        assertEquals(setOf("post"), first.unseenStreamIds)
        assertEquals(setOf("post"), first.pendingNewStatusIds)
        assertTrue(next.unseenStreamIds.isEmpty())
        assertTrue(next.isLoadingMore)
    }

    @Test fun editsNewPostsAndWordChangesReuseHtmlWithoutKeepingStaleDecisions() {
        var conversions = 0
        val cache = TimelineModerationCache(plainText = { conversions++; it.removePrefix("<p>").removeSuffix("</p>") })
        val post = testStatus().copy(contentHtml = "<p>Hello & goodbye</p>")
        var state = TimelineUiState(statuses = listOf(post))
        fun moderation(word: String) = AccountModerationState(wordMutes = mapOf(testAccount.sessionId to listOf(word)))
        assertEquals(1, cache.apply(state, moderation("spoiler"), testAccount).statuses.size)
        state = state.copy(statuses = listOf(post.copy(favourited = true), post.copy(timelineId = "boost")))
        assertTrue(cache.apply(state, moderation("spoiler"), testAccount).statuses.first().favourited)
        assertEquals(1, conversions)
        assertTrue(cache.apply(state, moderation("GOODBYE"), testAccount).statuses.isEmpty())
        assertEquals(1, conversions)
        assertEquals(2, cache.apply(state, AccountModerationState(), testAccount).statuses.size)
        state = state.copy(statuses = listOf(post.copy(spoilerText = "spoiler")))
        assertTrue(cache.apply(state, moderation("spoiler"), testAccount).statuses.isEmpty())
        state = state.copy(statuses = listOf(post.copy(contentHtml = "<p>spoiler</p>")))
        assertTrue(cache.apply(state, moderation("spoiler"), testAccount).statuses.isEmpty())
        assertEquals(2, conversions)
    }

    @Test fun cleanupDoesNotDiscardWordOrServerHiddenPostsAndRelationshipChangesInvalidateRows() {
        val cache = TimelineModerationCache()
        val rule = ServerFilter("rule", "hidden", setOf("home"), action = "hide")
        val post = testStatus().copy(filtered = listOf(StatusFilterResult(rule)))
        val source = TimelineUiState(statuses = listOf(post))
        val moderation = AccountModerationState(wordMutes = mapOf(testAccount.sessionId to listOf("Hello")))
        assertTrue(cache.apply(source, moderation, testAccount).statuses.isEmpty())
        val cleanup = AccountModerationState(applyServerFilters = false)
        assertEquals(source.statuses, cache.apply(source, cleanup, testAccount).statuses)
        val blocked = cleanup.changed(testAccount, post.author.id, AccountRelationship(blocking = true))
        assertTrue(cache.apply(source, blocked, testAccount).statuses.isEmpty())
        assertEquals(source.statuses, cache.apply(source, cleanup, testAccount).statuses)
    }

    @Test fun filterExpiryClockChangesContextAndRuleRemovalInvalidateThePresentation() {
        var now = Instant.parse("2026-10-19T00:00:00Z")
        val initial = now
        val cache = TimelineModerationCache(clock = { now })
        val rule = ServerFilter("rule", "hidden", setOf("home"), expiresAt = now.plusSeconds(10).toString(), action = "hide")
        val source = TimelineUiState(statuses = listOf(testStatus().copy(filtered = listOf(StatusFilterResult(rule)))))
        assertTrue(cache.apply(source, AccountModerationState(), testAccount).statuses.isEmpty())
        now = now.plusSeconds(10)
        assertEquals(1, cache.apply(source, AccountModerationState(), testAccount).statuses.size)
        now = initial
        assertTrue(cache.apply(source, AccountModerationState(), testAccount).statuses.isEmpty())
        assertEquals(1, cache.apply(source.copy(selectedFeed = TimelineFeed.Local), AccountModerationState(), testAccount).statuses.size)
        val removed = AccountModerationState(serverFilters = mapOf(testAccount.sessionId to emptyList()))
        assertEquals(1, cache.apply(source, removed, testAccount).statuses.size)
        assertTrue(cache.apply(source, AccountModerationState(), testAccount).statuses.isEmpty())
    }

    @Test fun changingSessionAndClearingTheCacheDropParsedText() {
        var conversions = 0
        val cache = TimelineModerationCache(plainText = { conversions++; it })
        val source = TimelineUiState(statuses = listOf(testStatus()))
        val moderation = AccountModerationState(wordMutes = mapOf(
            testAccount.sessionId to listOf("Hello"), secondAccount.sessionId to listOf("other")))
        assertTrue(cache.apply(source, moderation, testAccount).statuses.isEmpty())
        assertEquals(1, cache.apply(source, moderation, secondAccount).statuses.size)
        assertTrue(cache.apply(source, moderation, testAccount).statuses.isEmpty())
        cache.clear()
        cache.apply(source, moderation, testAccount)
        assertEquals(4, conversions)
    }

    @Test fun quoteStatusFollowsAccountModerationWordMuteAndServerFilters() {
        val cache = TimelineModerationCache(plainText = { it.removePrefix("<p>").removeSuffix("</p>") })
        val quoted = testStatus("quoted").copy(
            author = StatusAuthor("quoted-author", "Quoted", "quoted@example.social", ""),
            contentHtml = "<p>Secret spoiler quote</p>",
        )
        val parent = testStatus("parent").copy(quote = StatusQuote("accepted", quoted, quoted.statusId))
        val source = TimelineUiState(statuses = listOf(parent))

        val visible = cache.apply(source, AccountModerationState(), testAccount).statuses.single()
        assertEquals("quoted", visible.quote?.status?.statusId)

        val muted = AccountModerationState().changed(testAccount, "quoted-author", AccountRelationship(muting = true))
        val afterAccountMute = cache.apply(source, muted, testAccount).statuses.single()
        assertEquals("muted_account", afterAccountMute.quote?.state)
        assertNull(afterAccountMute.quote?.status)

        val wordMuted = AccountModerationState(wordMutes = mapOf(testAccount.sessionId to listOf("spoiler")))
        val afterWordMute = cache.apply(source, wordMuted, testAccount).statuses.single()
        assertEquals("filtered", afterWordMute.quote?.state)
        assertNull(afterWordMute.quote?.status)

        val warnRule = ServerFilter("warn-rule", "CW warning", setOf("home"), action = "warn")
        val warnedQuote = quoted.copy(filtered = listOf(StatusFilterResult(warnRule)))
        val warnedState = TimelineUiState(statuses = listOf(parent.copy(quote = StatusQuote("accepted", warnedQuote, warnedQuote.statusId))))
        val afterWarn = cache.apply(warnedState, AccountModerationState(), testAccount).statuses.single()
        assertEquals(listOf("CW warning"), afterWarn.quote?.status?.filterDisplay?.warnings?.map { it.title })

        val hideRule = ServerFilter("hide-rule", "Hidden", setOf("home"), action = "hide")
        val hiddenQuote = quoted.copy(filtered = listOf(StatusFilterResult(hideRule)))
        val hiddenState = TimelineUiState(statuses = listOf(parent.copy(quote = StatusQuote("accepted", hiddenQuote, hiddenQuote.statusId))))
        val afterHide = cache.apply(hiddenState, AccountModerationState(), testAccount).statuses.single()
        assertEquals("filtered", afterHide.quote?.state)
        assertNull(afterHide.quote?.status)
    }
}
