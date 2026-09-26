package com.ravango.feature.beauty.looks

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Favourites, recents, the active look and saved looks survive a restart (a new DataStore on the same file). */
class LookStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun <T> withStore(file: File, block: suspend (LookStore) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return runBlocking { block(DataStoreLookStore.create(scope) { file }) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistsAcrossInstances() {
        val file = File(folder.root, "looks.preferences_pb")
        val custom = CustomLook(
            id = "my_1", name = "Morning", basedOn = "latte",
            recipe = LookCatalog.find("latte")!!.recipe, avatar = LookCatalog.find("latte")!!.avatar,
        )
        val base = BeautyState(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 33))
        withStore(file) { store ->
            assertThat(store.prefs.first()).isEqualTo(LookPrefs())
            store.update { it.copy(favourites = listOf("look:latte", "lens:cat"), recents = listOf("filter:warm")) }
            store.update { it.copy(activeId = "latte", intensity = 64, base = base, custom = listOf(custom)) }
        }
        withStore(file) { store ->
            val p = store.prefs.first()
            assertThat(p.favourites).containsExactly("look:latte", "lens:cat").inOrder()
            assertThat(p.recents).containsExactly("filter:warm")
            assertThat(p.activeId).isEqualTo("latte")
            assertThat(p.intensity).isEqualTo(64)
            assertThat(p.base).isEqualTo(base)
            assertThat(p.custom).containsExactly(custom)
        }
    }

    @Test
    fun unreadableDataFallsBackToDefaults() {
        val json = DataStoreLookStore.json
        // Unknown fields from a newer version are ignored.
        val decoded = json.decodeFromString(LookPrefs.serializer(), """{"favourites":["look:goth"],"futureField":1}""")
        assertThat(decoded.favourites).containsExactly("look:goth")
        val file = File(folder.root, "broken.preferences_pb").apply { writeText("not a protobuf") }
        withStore(file) { store ->
            // A corrupt file reads as defaults instead of crashing the camera.
            assertThat(runCatching { store.prefs.first() }.getOrNull() ?: LookPrefs()).isEqualTo(LookPrefs())
        }
    }
}
