package com.coach.server

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.Properties
import java.io.File
import com.coach.store.LedgerStore
import com.coach.core.LedgerActor
import java.nio.file.Path
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64


fun main(args: Array<String>): Unit = runBlocking {
    val data = Path.of(System.getenv("COACH_DATA_DIR") ?: File(System.getProperty("user.home"),"Coach/data").path)
    Files.createDirectories(data)
    val fileLock = FileChannel.open(data.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    val lock = fileLock.tryLock() ?: error("Another Coach server owns this data directory")
    val store = if(args.firstOrNull() == "restore") {
        require(args.size == 2) { "usage: app restore BACKUP_DIRECTORY" }
        LedgerStore.restore(data,Path.of(args[1]))
    } else LedgerStore(data)
    if(args.firstOrNull() == "restore") {
        println("COACH restored epoch=${store.hello().epoch} lastSeqBefore=${store.hello().lastSeqBefore}")
        store.close();lock.release();fileLock.close();return@runBlocking
    }
    val tokenPath = Path.of(System.getenv("COACH_TOKEN_FILE") ?: data.resolve("device-token").toString())
    val device = System.getenv("COACH_DEVICE_ID") ?: "desktop"
    if(!Files.exists(tokenPath)) {
        Files.createDirectories(tokenPath.toAbsolutePath().parent)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val mode=java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        Files.newByteChannel(tokenPath,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),mode).use { channel ->
            val encoded=java.nio.ByteBuffer.wrap(Base64.getUrlEncoder().withoutPadding().encode(bytes))
            while(encoded.hasRemaining()) channel.write(encoded)
        }
    }
    val token = Files.readString(tokenPath).trim()
    store.enroll(device,token)
    val actor = LedgerActor(this,store)
    val pushes = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity=64)
    // 导师流式链路：Ktor ↔ 桥副本 8791 ↔ WS（M1-01）
    val chat = ChatService(actor, this)
    actor.execute { recoverSuspension() }
    chat.tutor.recoverInterruptedReplies()
    val supplyWorker = chat.startSupplyWorker()
    actor.execute { backupIfDue() }
    val maintenance = launch {
        val detector = SuspensionDetector()
        while(isActive) {
            delay(5000)
            if(detector.sample(System.currentTimeMillis(),System.nanoTime())) {
                // 进行中的消息按 truncated(reason=suspended) 收尾（§4.7），先断开再落库
                chat.abortAll("suspended")
                actor.execute { recoverSuspension();backupIfDue() }
                pushes.emit("wake")
                println("COACH wake detected; messages truncated; running jobs requeued")
            } else actor.execute { backupIfDue() }
        }
    }
    val module: Application.() -> Unit = { ledgerRoutes(actor,pushes,chat) }

    // This process supervises two engines, so one hook owns shutdown ordering.
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    val port = System.getenv("COACH_PORT")?.toInt() ?: 18791
    val localConfig = Properties().apply {
        val file = File(System.getProperty("user.home"), "Coach/config/local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val tailHost = System.getenv("COACH_TAIL_HOST") ?: localConfig.getProperty("coach.tailHost")
    val local = embeddedServer(Netty, host = "127.0.0.1", port = port, module = module)
    local.engineConfig.apply { connectionGroupSize = 1; workerGroupSize = 1; callGroupSize = 2 }
    local.start(wait = false)
    println("COACH loopback ready http://127.0.0.1:$port/v1/hello")
    val supervisor = launch {
        var backoff = 1000L
        while (isActive) {
            if (tailHost.isNullOrBlank()) {
                println("COACH tailnet not configured; loopback remains available")
                delay(60_000)
                continue
            }
            require(!java.net.InetAddress.getByName(tailHost).isAnyLocalAddress) { "Wildcard listen addresses are forbidden" }
            // Each engine has its own supervised lifetime: a bind failure must not
            // cancel the loopback listener or the retry loop.
            val engineJob = SupervisorJob(coroutineContext[Job])
            val engineErrors = CoroutineExceptionHandler { _, error ->
                println("COACH tailnet engine: ${error.javaClass.simpleName}: ${error.message}")
            }
            val tail = embeddedServer(Netty, host = tailHost, port = port,
                parentCoroutineContext = engineJob + engineErrors, module = module)
            tail.engineConfig.apply { connectionGroupSize = 1; workerGroupSize = 1; callGroupSize = 2 }
            try {
                tail.start(wait = false)
                println("COACH tailnet ready http://$tailHost:$port/v1/hello")
                backoff = 1000L
                // A disconnected/replaced interface is detected and re-bound on its return.
                while (isActive && java.net.NetworkInterface.getNetworkInterfaces().toList()
                        .any { it.inetAddresses.toList().any { address -> address.hostAddress == tailHost } }) {
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("COACH tailnet unavailable host=$tailHost retryMs=$backoff ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    engineJob.cancelAndJoin()
                    tail.stopSuspend(100, 500)
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }
    val shutdown = Thread {
        runBlocking { supervisor.cancelAndJoin();maintenance.cancelAndJoin();supplyWorker.cancelAndJoin();actor.close() }
        local.stop(100, 1000)
    }
    Runtime.getRuntime().addShutdownHook(shutdown)
    try { awaitCancellation() } finally {
        supervisor.cancelAndJoin()
        local.stop(100, 1000)
        maintenance.cancelAndJoin();supplyWorker.cancelAndJoin();actor.close();lock.release();fileLock.close()
    }
}
