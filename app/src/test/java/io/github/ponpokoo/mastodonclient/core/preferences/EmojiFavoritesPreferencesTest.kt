package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EmojiFavoritesPreferencesTest {
    @Test fun favoritesSurviveStoreRecreationWithoutDuplicatesOrHistoryTruncation() = runTest {
        val data = MemoryAuthPreferences()
        val store = UserPreferencesStore(data)
        val names = listOf("😊") + (0 until 25).map { "custom_$it" }
        assertTrue(store.emojiFavorites.first().isEmpty())
        names.forEach { store.setEmojiFavorite("a", it, true) }
        store.setEmojiFavorite("a", "😊", true)
        store.setEmojiFavorite("b", "other", true)
        store.recordReaction("a", "history")
        store.recordComposerEmoji("a", "compose")
        val restored = UserPreferencesStore(data)
        assertEquals(names, restored.emojiFavorites.first()["a"])
        assertEquals(listOf("other"), restored.emojiFavorites.first()["b"])
        assertEquals(names - "custom_3", restored.setEmojiFavorite("a", "custom_3", false))
        assertEquals(names - "custom_3", UserPreferencesStore(data).emojiFavorites.first()["a"])
        assertEquals(listOf("history"), restored.reactionHistory.first()["a"])
        assertEquals(listOf("compose"), restored.composerEmojiHistory.first()["a"])
    }

    @Test fun concurrentChangesMergeAndAnEmptyAccountDoesNotRemoveOtherAccounts() = runTest {
        val store = UserPreferencesStore(MemoryAuthPreferences())
        (0 until 30).map { async { store.setEmojiFavorite("a", "emoji_$it", true) } }.awaitAll()
        assertEquals((0 until 30).map { "emoji_$it" }.toSet(), store.emojiFavorites.first().getValue("a").toSet())
        store.setEmojiFavorite("b", "😊", true)
        (0 until 30).map { async { store.setEmojiFavorite("a", "emoji_$it", false) } }.awaitAll()
        assertEquals(mapOf("b" to listOf("😊")), store.emojiFavorites.first())
    }

    @Test fun failedSaveDoesNotReplaceThePersistedFavorites() = runTest {
        val memory = MemoryAuthPreferences()
        var failWrites = false
        val data = object : DataStore<Preferences> {
            override val data = memory.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw java.io.IOException("Fixture write failure")
                return memory.updateData(transform)
            }
        }
        val store = UserPreferencesStore(data)
        store.setEmojiFavorite("a", "😊", true)
        failWrites = true
        assertTrue(runCatching { store.setEmojiFavorite("a", "custom", true) }.isFailure)
        assertEquals(listOf("😊"), UserPreferencesStore(memory).emojiFavorites.first()["a"])
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun unrelatedPreferenceWritesDoNotReemitEmojiHistoryOrFavorites() = runTest {
        val store = UserPreferencesStore(MemoryAuthPreferences())
        store.setEmojiFavorite("a", "😊", true)
        store.recordReaction("a", "🔥")
        store.recordComposerEmoji("a", "🎉")

        val favoritesEmissions = mutableListOf<Map<String, List<String>>>()
        val reactionEmissions = mutableListOf<Map<String, List<String>>>()
        val composerEmissions = mutableListOf<Map<String, List<String>>>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val jobs = listOf(
            backgroundScope.launch(dispatcher) { store.emojiFavorites.collect(favoritesEmissions::add) },
            backgroundScope.launch(dispatcher) { store.reactionHistory.collect(reactionEmissions::add) },
            backgroundScope.launch(dispatcher) { store.composerEmojiHistory.collect(composerEmissions::add) },
        )

        store.setOpenLinksInApp(false)
        store.setThemeMode(ThemeMode.Dark)

        assertEquals(1, favoritesEmissions.size)
        assertEquals(1, reactionEmissions.size)
        assertEquals(1, composerEmissions.size)

        store.setEmojiFavorite("a", "✨", true)
        assertEquals(2, favoritesEmissions.size)
        assertEquals(1, reactionEmissions.size)
        assertEquals(1, composerEmissions.size)
        jobs.forEach { it.cancel() }
    }
}
