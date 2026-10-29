package com.coach.android
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coach.domain.*
import com.coach.client.ProjectionCache
import com.coach.client.db.ClientDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class LedgerReplayBenchmark {
    @Test fun coldReplay() {
        val phase=InstrumentationRegistry.getArguments().getString("coachReplayPhase", "all")
        if(phase=="cache") { cachedOpenInFreshProcess();return }
        val events=ReplayBenchmark.events()
        val start=System.nanoTime()
        val state=project(events)
        val ms=(System.nanoTime()-start)/1000000
        assertEquals(50000L,state.lastSeq)
        assertTrue(state.mastery.values.all { it.passed && it.solid })
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-replay-cache.sqlite"
        context.deleteDatabase(name)
        var driver=AndroidSqliteDriver(ClientDatabase.Schema,context,name)
        var db=ClientDatabase(driver)
        db.transaction {
            for(e in events) db.ledgerQueries.putEvent(e.seq,e.courseId,LedgerJson.encodeToString(e))
            ProjectionCache(db).put("synthetic","benchmark-epoch",state.lastSeq,state)
            db.ledgerQueries.putMeta("expectedHash",hash(state.bytes()))
        }
        driver.close()
        val cachedStart=System.nanoTime()
        driver=AndroidSqliteDriver(ClientDatabase.Schema,context,name)
        db=ClientDatabase(driver)
        val cached=ProjectionCache(db).get("synthetic","benchmark-epoch",db.ledgerQueries.replicaSeq("synthetic").executeAsOne())!!
        val cachedMs=(System.nanoTime()-cachedStart)/1000000
        assertEquals(state.bytes(),cached.bytes())
        assertNull(ProjectionCache(db).get("synthetic","different-epoch",state.lastSeq))
        assertNull(ProjectionCache(db).get("synthetic","benchmark-epoch",state.lastSeq+1))
        driver.close()
        if(phase!="prepare") context.deleteDatabase(name)
        android.util.Log.i("CoachReplay","ANDROID coldReplay events=50000 ms=$ms cachedOpenMs=$cachedMs passed=${state.mastery.values.count { it.passed }} sdk=${android.os.Build.VERSION.SDK_INT}")
    }
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun cachedOpenInFreshProcess() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val start=System.nanoTime()
        val driver=AndroidSqliteDriver(ClientDatabase.Schema,context,"synthetic-replay-cache.sqlite")
        val db=ClientDatabase(driver)
        val seq=db.ledgerQueries.replicaSeq("synthetic").executeAsOne()
        val state=ProjectionCache(db).get("synthetic","benchmark-epoch",seq)!!
        val ms=(System.nanoTime()-start)/1000000
        assertEquals(50000L,state.lastSeq)
        assertEquals(db.ledgerQueries.getMeta("expectedHash").executeAsOne(),hash(state.bytes()))
        android.util.Log.i("CoachReplay","ANDROID cachedFreshProcess events=50000 ms=$ms projectionHashEqual=true sdk=${android.os.Build.VERSION.SDK_INT}")
        driver.close();context.deleteDatabase("synthetic-replay-cache.sqlite")
    }

}
