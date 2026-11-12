package com.coach.android

import com.coach.ui.ReaderPageTrace
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic only. available() return is an application observation, never packet arrival. */
internal class ReaderSocketQueueWatch(private val input: InputStream) {
    private val stop=AtomicBoolean(false)
    private val written=AtomicLong(0)
    private val ready=CountDownLatch(1)
    private val queries=JSONArray()
    private var readyNs=0L
    private var tid=0
    private var error: String?=null
    private var joined=false
    private var forcedClose=false
    private var joinedNs=0L
    private val startedNs=ReaderPageTrace.nowNs()
    private val worker=Thread({
        tid=android.os.Process.myTid()
        readyNs=ReaderPageTrace.nowNs()
        ready.countDown()
        try {
            while (!stop.get() && queries.length()<512) {
                if (written.get()!=0L) {
                    val invoked=ReaderPageTrace.nowNs()
                    val count=input.available()
                    val returned=ReaderPageTrace.nowNs()
                    queries.put(JSONObject().put("invokedNs",invoked).put("returnedNs",returned).put("availableBytes",count))
                }
                if (!stop.get()) LockSupport.parkNanos(2_000_000L)
            }
        } catch (failure: Exception) { error=failure.javaClass.simpleName }
    },"reader-queue-watch")

    fun start() {
        worker.start()
        check(ready.await(3,TimeUnit.SECONDS)) { "queue observer did not start" }
    }
    fun written(ns: Long) { written.set(ns) }
    fun stop() { stop.set(true) }
    fun finish(socket: Socket) {
        if (joined) return
        stop()
        worker.join(1000)
        if (worker.isAlive) { forcedClose=true;socket.close();worker.join() }
        joined=true;joinedNs=ReaderPageTrace.nowNs()
    }
    fun report(): JSONObject {
        check(joined)
        return JSONObject().put("mode","available").put("startedNs",startedNs).put("readyNs",readyNs)
            .put("observerTid",tid).put("inputClass",input.javaClass.name).put("joined",joined).put("joinedNs",joinedNs)
            .put("forcedSocketClose",forcedClose).put("error",error ?: JSONObject.NULL)
            .put("intervalRequestedNs",2_000_000L).put("queryLimit",512).put("limitReached",queries.length()==512)
            .put("queries",queries)
    }
}
