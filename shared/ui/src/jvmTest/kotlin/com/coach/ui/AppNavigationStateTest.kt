package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.test.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AppNavigationStateTest {
    @Test fun upgradeRestoresTheOldHolderKeysIncludingSourceAndBackToTheOriginalQuestion() = runComposeUiTest {
        // Mount with literal pre-upgrade keys first, then recreate the entire holder/registry with migrated keys.
        val old = """[{"tab":"REVIEW"},{"page":"STUDY","tab":"REVIEW","id":"SYN-KP","review":true,"week":3,"label":"合成节"},{"page":"SOURCE","tab":"REVIEW","id":"SYN-DOC","pdfPage":4,"label":"原文"}]"""
        var generation by mutableStateOf(0)
        var mounted by mutableStateOf(true)
        var navigation by mutableStateOf<AppNavigation?>(null)
        var restored: Map<String, List<Any?>>? = null
        lateinit var registry: SaveableStateRegistry
        val choice = mutableStateOf("unset")
        val result = mutableStateOf("unset")
        val pending = mutableStateOf("unset")
        val scroll = mutableStateOf(0)
        val page = mutableStateOf(1)
        setContent {
            if (mounted) {
                val stateRegistry = remember { SaveableStateRegistry(restored) { true } }
                SideEffect { registry = stateRegistry }
                CompositionLocalProvider(LocalSaveableStateRegistry provides stateRegistry) {
                    val holder = rememberSaveableStateHolder()
                    val nav = navigation
                    val study = nav?.entries?.lastOrNull { it.page == AppPage.STUDY }
                    Column {
                        holder.SaveableStateProvider(study?.stateKey("C") ?: "C:STUDY:REVIEW:SYN-KP:true:3") {
                            var selected by rememberSaveable { mutableStateOf(if (generation == 0) "B" else "lost") }
                            var graded by rememberSaveable { mutableStateOf(if (generation == 0) "incorrect:SYN-ATTEMPT" else "lost") }
                            var waiting by rememberSaveable { mutableStateOf(if (generation == 0) "SYN-PENDING" else "lost") }
                            var offset by rememberSaveable { mutableStateOf(if (generation == 0) 314 else 0) }
                            SideEffect { choice.value = selected; result.value = graded; pending.value = waiting; scroll.value = offset }
                            BasicText("$selected / $graded / $waiting / $offset")
                        }
                        if (nav == null || nav.current.page == AppPage.SOURCE) {
                            holder.SaveableStateProvider(nav?.current?.sourceStateKey("C") ?: "C:AppEntry(page=SOURCE, tab=REVIEW, id=SYN-DOC, autoStart=false, review=false, week=null, pdfPage=4, label=原文)") {
                                var at by rememberSaveable { mutableStateOf(if (generation == 0) 4 else 1) }
                                SideEffect { page.value = at }
                                BasicText("source:$at")
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle {
            restored = registry.performSave()
            navigation = AppNavigation.restore(assertNotNull(AppNavigation.restore(old)).encode())
            generation++
            mounted = false
        }
        waitForIdle()
        runOnIdle { mounted = true }
        waitForIdle()
        onNodeWithText("source:4").assertExists()
        assertEquals(4, page.value)
        runOnIdle { navigation!!.back() }
        waitForIdle()
        onNodeWithText("source:4").assertDoesNotExist()
        assertEquals("SYN-KP", navigation!!.current.id)
        assertEquals("B", choice.value)
        assertEquals("incorrect:SYN-ATTEMPT", result.value)
        assertEquals("SYN-PENDING", pending.value)
        assertEquals(314, scroll.value)
    }
}
