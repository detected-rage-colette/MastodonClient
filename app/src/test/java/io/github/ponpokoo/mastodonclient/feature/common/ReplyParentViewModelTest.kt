package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.RequestException
import io.github.ponpokoo.mastodonclient.domain.model.RequestFailure
import io.github.ponpokoo.mastodonclient.domain.model.ServerAccountSettings
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.StatusFilterResult
import io.github.ponpokoo.mastodonclient.domain.model.StatusQuote
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.ServerFilter
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyParentViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun resolvesFromMemoryAndHomeCacheWithoutNetworkCalls() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        var remoteCalls = 0
        val memoryParent = testStatus("mem-parent")
        val homeParent = testStatus("home-parent")
        val repo = object : TimelineRepository {
            override fun getCachedStatus(session: AccountSession, statusId: String): TimelineStatus? =
                memoryParent.takeIf { statusId == "mem-parent" }
            override suspend fun getCachedHomeStatus(session: AccountSession, statusId: String): TimelineStatus? =
                homeParent.takeIf { statusId == "home-parent" }
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String): Result<TimelineStatus> {
                remoteCalls++
                return Result.failure(IllegalStateException("unexpected remote call"))
            }
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                error("unused")
        }
        val model = ReplyParentViewModel(repo, browsing)
        advanceUntilIdle()

        val memPeek = model.peek("mem-parent") as ReplyParentState.Available
        assertEquals("mem-parent", memPeek.status.statusId)
        model.ensureLoaded("mem-parent")
        model.ensureLoaded("home-parent")
        advanceUntilIdle()

        val homeState = model.observe("home-parent").first() as ReplyParentState.Available
        assertEquals("home-parent", homeState.status.statusId)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun deduplicatesConcurrentRemoteFetchesAndSuppressesRepeatAfterFailure() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val gate = CompletableDeferred<Unit>()
        var calls = mutableListOf<String>()
        val repo = object : TimelineRepository {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String): Result<TimelineStatus> {
                calls += timelineId
                if (timelineId == "ok") {
                    gate.await()
                    return Result.success(
                        testStatus("ok").copy(
                            quote = StatusQuote("accepted", testStatus("inner"), "inner"),
                        ),
                    )
                }
                return Result.failure(RequestException(RequestFailure.NotFound, "404", RuntimeException("404")))
            }
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                error("unused")
        }
        val model = ReplyParentViewModel(repo, browsing)
        advanceUntilIdle()

        val first = async { model.ensureLoaded("ok") }
        val second = async { model.ensureLoaded("ok") }
        advanceUntilIdle()
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("ok"), calls)
        val available = model.observe("ok").first() as ReplyParentState.Available
        assertEquals("ok", available.status.statusId)
        assertNull(available.status.quote)

        model.ensureLoaded("missing")
        model.ensureLoaded("missing")
        assertEquals(listOf("ok", "missing"), calls)
        assertEquals(ReplyParentState.Unavailable, model.observe("missing").first())
    }

    @Test
    fun rateLimitCooldownBlocksSubsequentFetchesUntilExpiry() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        var now = 1_000L
        val calls = mutableListOf<String>()
        val repo = object : TimelineRepository {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String): Result<TimelineStatus> {
                calls += timelineId
                return if (calls.size == 1) {
                    Result.failure(RequestException(RequestFailure.RateLimited, "429", RuntimeException("429")))
                } else {
                    Result.success(testStatus(timelineId))
                }
            }
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                error("unused")
        }
        val model = ReplyParentViewModel(repo, browsing, clockMillis = { now }, rateLimitCooldownMillis = 10_000L)
        advanceUntilIdle()

        model.ensureLoaded("p1")
        assertEquals(ReplyParentState.Unavailable, model.observe("p1").first())

        now = 5_000L
        model.ensureLoaded("p2")
        assertEquals(listOf("p1"), calls)
        assertEquals(ReplyParentState.Unavailable, model.observe("p2").first())

        now = 12_000L
        model.ensureLoaded("p3")
        assertEquals(listOf("p1", "p3"), calls)
        assertTrue(model.observe("p3").first() is ReplyParentState.Available)
    }

    @Test
    fun switchingSessionsDiscardsInFlightResponseAndClearsCache() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val gate = CompletableDeferred<Unit>()
        val repo = object : TimelineRepository {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String): Result<TimelineStatus> {
                if (session == testAccount) gate.await()
                return Result.success(testStatus(timelineId).copy(author = StatusAuthor("a", session.sessionId, session.sessionId, "")))
            }
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                error("unused")
        }
        val model = ReplyParentViewModel(repo, browsing)
        advanceUntilIdle()

        val oldFetch = async { model.ensureLoaded("shared") }
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        gate.complete(Unit)
        runCatching { oldFetch.await() }
        advanceUntilIdle()

        assertEquals(ReplyParentState.Loading, model.peek("shared"))
        model.ensureLoaded("shared")
        val newState = model.observe("shared").first() as ReplyParentState.Available
        assertEquals(secondAccount.sessionId, newState.status.author.displayName)
    }

    @Test
    fun appliesModerationWordMutesAndServerFiltersToResolvedParent() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val modFlow = MutableStateFlow(AccountModerationState())
        val wordsFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        val settingsFlow = MutableStateFlow<Map<String, ServerAccountSettings>>(emptyMap())
        val warnFilter = ServerFilter("w", "Warn", setOf("home"), action = "warn")
        val parent = testStatus("p").copy(
            author = StatusAuthor("p-author", "Parent", "parent@example.social", ""),
            contentHtml = "<p>Spoiler topic</p>",
            filtered = listOf(StatusFilterResult(warnFilter)),
        )
        val repo = object : TimelineRepository {
            override val moderation = modFlow
            override val wordMutes = wordsFlow
            override val serverSettings = settingsFlow
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) = Result.success(parent)
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = error("unused")
        }
        val model = ReplyParentViewModel(
            repo,
            browsing,
            plainText = { it.replace(Regex("<[^>]+>"), "") },
        )
        advanceUntilIdle()

        model.ensureLoaded("p")
        val initial = model.observe("p").first() as ReplyParentState.Available
        assertEquals(listOf("Warn"), initial.status.filterDisplay.warnings.map { it.title })

        wordsFlow.value = mapOf(testAccount.sessionId to listOf("spoiler"))
        advanceUntilIdle()
        assertEquals(ReplyParentState.Unavailable, model.observe("p").first())

        wordsFlow.value = emptyMap()
        modFlow.value = AccountModerationState().changed(testAccount, "p-author", AccountRelationship(muting = true))
        advanceUntilIdle()
        assertEquals(ReplyParentState.Unavailable, model.observe("p").first())
    }
}
