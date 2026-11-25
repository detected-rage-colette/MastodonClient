package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.RequestFailure
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.model.requestFailure
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

sealed interface ReplyParentState {
    data object Loading : ReplyParentState
    data class Available(val status: TimelineStatus) : ReplyParentState
    data object Unavailable : ReplyParentState
}

interface ReplyParentResolver {
    fun peek(statusId: String): ReplyParentState
    fun observe(statusId: String): Flow<ReplyParentState>
    suspend fun ensureLoaded(statusId: String)
}

internal val LocalReplyParentResolver = staticCompositionLocalOf<ReplyParentResolver?> { null }

/**
 * Resolves reply-parent statuses on demand for visible timeline items, isolated by browsing session.
 */
class ReplyParentViewModel(
    private val repository: TimelineRepository,
    browsing: BrowsingSession,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val maxConcurrentFetches: Int = 4,
    private val rateLimitCooldownMillis: Long = 30_000L,
    private val plainText: (String) -> String = ::moderationPlainText,
) : SessionScopedViewModel(browsing), ReplyParentResolver {
    private val stateMutex = Mutex()
    private val fetchSemaphore = Semaphore(maxConcurrentFetches)
    private var moderationTextCache = ModerationTextCache(convert = plainText)
    private var sessionGeneration = 0L
    private var rateLimitedUntilMillis = 0L

    private val rawParents = boundedLruMap<String, TimelineStatus>(500)
    private val failedIds = linkedSetOf<String>()
    private val temporarilyUnavailableIds = linkedSetOf<String>()
    private val inFlight = mutableMapOf<String, Deferred<Unit>>()

    private val resolvedFlow = MutableStateFlow<Map<String, ReplyParentState>>(emptyMap())

    @Volatile
    private var latestModeration = AccountModerationState()

    init {
        observeSession()
        viewModelScope.launch {
            combine(
                repository.moderation,
                repository.serverSettings,
                repository.wordMutes,
            ) { moderation, settings, words ->
                val snapshot = browsing.snapshot.value
                val account = snapshot.account
                val filters = if (account == null) emptyMap()
                else settings[account.sessionId]?.takeIf { it.belongsTo(account) }?.filters
                    ?.let { mapOf(account.sessionId to it) }.orEmpty()
                moderation.copy(serverFilters = filters, wordMutes = words)
            }.collect { combined ->
                latestModeration = combined
                stateMutex.withLock {
                    publishLocked(activeSnapshot())
                }
            }
        }
    }

    private fun activeSnapshot(): BrowsingSession.Snapshot? =
        browsing.snapshot.value.takeIf { it.account != null }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        sessionGeneration++
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        rawParents.clear()
        failedIds.clear()
        temporarilyUnavailableIds.clear()
        rateLimitedUntilMillis = 0L
        moderationTextCache = ModerationTextCache(convert = plainText)
        resolvedFlow.value = emptyMap()
    }

    override fun onChange(change: BrowsingSession.Change) {
        val snapshot = activeSnapshot() ?: return
        viewModelScope.launch {
            stateMutex.withLock {
                if (activeSnapshot() != snapshot) return@withLock
                when (change) {
                    is BrowsingSession.Change.StatusDeleted -> {
                        rawParents.remove(change.statusId)
                        failedIds.add(change.statusId)
                        trimSet(failedIds, 500)
                        publishLocked(snapshot)
                    }
                    is BrowsingSession.Change.StatusUpdated -> {
                        val existing = rawParents[change.status.statusId] ?: return@withLock
                        rawParents[change.status.statusId] = change.applyTo(existing)
                        publishLocked(snapshot)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun peek(statusId: String): ReplyParentState {
        resolvedFlow.value[statusId]?.let { return it }
        val snapshot = activeSnapshot() ?: return ReplyParentState.Unavailable
        val account = snapshot.account ?: return ReplyParentState.Unavailable
        val cached = repository.getCachedStatus(account, statusId) ?: return ReplyParentState.Loading
        return presentForAccount(account, cached)
    }

    override fun observe(statusId: String): Flow<ReplyParentState> =
        resolvedFlow.map { map ->
            map[statusId] ?: peek(statusId)
        }.distinctUntilChanged()

    override suspend fun ensureLoaded(statusId: String) {
        val snapshot = activeSnapshot() ?: return
        val account = snapshot.account ?: return
        val generation = sessionGeneration

        val existingDeferred = stateMutex.withLock {
            if (generation != sessionGeneration || activeSnapshot() != snapshot) return
            if (statusId in rawParents || statusId in failedIds) return
            inFlight[statusId]
        }
        if (existingDeferred != null) {
            existingDeferred.await()
            return
        }

        // Check memory cache and local Home DB cache before acquiring a network permit.
        val localCached = repository.getCachedStatus(account, statusId)
            ?: repository.getCachedHomeStatus(account, statusId)
        currentCoroutineContext().ensureActive()
        if (localCached != null) {
            stateMutex.withLock {
                if (generation != sessionGeneration || activeSnapshot() != snapshot) return
                rawParents[statusId] = localCached
                temporarilyUnavailableIds.remove(statusId)
                publishLocked(snapshot)
            }
            return
        }

        val cooldownRemaining = stateMutex.withLock {
            if (generation != sessionGeneration || activeSnapshot() != snapshot) return
            if (statusId in rawParents || statusId in failedIds) return
            val now = clockMillis()
            if (now < rateLimitedUntilMillis) {
                temporarilyUnavailableIds.add(statusId)
                trimSet(temporarilyUnavailableIds, 500)
                publishLocked(snapshot)
                true
            } else {
                false
            }
        }
        if (cooldownRemaining) return

        // Acquire the semaphore in the caller's scope so items scrolled off-screen cancel immediately.
        fetchSemaphore.withPermit {
            currentCoroutineContext().ensureActive()
            val deferred = stateMutex.withLock {
                if (generation != sessionGeneration || activeSnapshot() != snapshot) return
                if (statusId in rawParents || statusId in failedIds) return
                inFlight[statusId] ?: requestScope.async {
                    fetchFromRemote(snapshot, generation, account, statusId)
                }.also { created ->
                    inFlight[statusId] = created
                }
            }
            deferred.await()
        }
    }

    private suspend fun fetchFromRemote(
        snapshot: BrowsingSession.Snapshot,
        generation: Long,
        account: AccountSession,
        statusId: String,
    ) {
        try {
            val result = repository.getTimelineStatus(account, statusId)
            currentCoroutineContext().ensureActive()
            if (activeSnapshot()?.account?.hasSameCredentials(account) != true ||
                activeSnapshot() != snapshot ||
                generation != sessionGeneration
            ) {
                throw CancellationException("Browsing session changed")
            }
            stateMutex.withLock {
                if (generation != sessionGeneration || activeSnapshot() != snapshot) return
                result.fold(
                    onSuccess = { fetched ->
                        rawParents[statusId] = fetched
                        failedIds.remove(statusId)
                        temporarilyUnavailableIds.remove(statusId)
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        if (error.requestFailure == RequestFailure.RateLimited) {
                            rateLimitedUntilMillis = clockMillis() + rateLimitCooldownMillis
                            temporarilyUnavailableIds.add(statusId)
                            trimSet(temporarilyUnavailableIds, 500)
                        } else {
                            failedIds.add(statusId)
                            trimSet(failedIds, 500)
                        }
                    },
                )
                publishLocked(snapshot)
            }
        } finally {
            stateMutex.withLock {
                if (generation == sessionGeneration) {
                    inFlight.remove(statusId)
                }
            }
        }
    }

    private fun publishLocked(snapshot: BrowsingSession.Snapshot?) {
        val account = snapshot?.account
        if (account == null) {
            resolvedFlow.value = emptyMap()
            return
        }
        val next = LinkedHashMap<String, ReplyParentState>(
            rawParents.size + failedIds.size + temporarilyUnavailableIds.size,
        )
        for ((id, status) in rawParents) {
            next[id] = presentForAccount(account, status)
        }
        for (id in failedIds) {
            next.putIfAbsent(id, ReplyParentState.Unavailable)
        }
        for (id in temporarilyUnavailableIds) {
            next.putIfAbsent(id, ReplyParentState.Unavailable)
        }
        resolvedFlow.value = next
    }

    private fun presentForAccount(account: AccountSession, status: TimelineStatus): ReplyParentState {
        val moderation = currentModerationFor(account)
        val presented = status.moderatedReference(
            m = moderation,
            s = account,
            textCache = moderationTextCache,
            context = "home",
        ) ?: return ReplyParentState.Unavailable
        // Reply parent card must not recursively render nested quotes or replies.
        return ReplyParentState.Available(presented.copy(quote = null))
    }

    private fun currentModerationFor(account: AccountSession): AccountModerationState {
        val base = repository.moderation.value
        val words = repository.wordMutes.value
        val settings = repository.serverSettings.value
        val filters = settings[account.sessionId]?.takeIf { it.belongsTo(account) }?.filters
            ?.let { mapOf(account.sessionId to it) }.orEmpty()
        return if (base == latestModeration && words == latestModeration.wordMutes && filters == latestModeration.serverFilters) {
            latestModeration
        } else {
            base.copy(serverFilters = filters, wordMutes = words)
        }
    }
}

private fun <K, V> boundedLruMap(maxSize: Int): LinkedHashMap<K, V> =
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

private fun <T> trimSet(set: LinkedHashSet<T>, maxSize: Int) {
    val iterator = set.iterator()
    while (set.size > maxSize && iterator.hasNext()) {
        iterator.next()
        iterator.remove()
    }
}
