package com.coach.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import com.coach.client.PairedCredentials
import com.coach.desktop.books.AndroidBookConnection
import com.coach.desktop.books.AndroidBookIdentity
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookExplanationRequest
import com.coach.desktop.books.BookLearningReply
import com.coach.desktop.books.BookPassage
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.ExplainResult
import com.coach.desktop.books.ExplainTrouble
import com.coach.desktop.books.PairedBookLearningSupport
import com.coach.desktop.books.StudyCoachCourse
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.bookSourceLabel
import com.coach.desktop.reader.DraftIdentity
import com.coach.desktop.reader.FileNoteDraftStore
import com.coach.desktop.reader.StoredDraft
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBookLearningTest {
    private lateinit var context: Context
    private lateinit var root: Path

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = Files.createTempDirectory("android-book-learning-")
        PDFBoxResourceLoader.init(context)
        Class.forName("org.sqlite.JDBC")
        TestAndroidKeyStoreProvider.install()
    }

    @After fun tearDown() {
        TestAndroidKeyStoreProvider.uninstall()
        root.toFile().deleteRecursively()
    }

    @Test fun consentAndDraftStoreWorkOnAndroidRuntime() {
        val consent = ExplainConsent(root.resolve("library"))
        assertFalse(consent.allowed("book-a"))
        consent.allow("book-a")
        assertTrue(ExplainConsent(root.resolve("library")).allowed("book-a"))

        val drafts = FileNoteDraftStore(root.resolve("drafts"), "local-library:test")
        val id = DraftIdentity("local-library:test", "book-a", "v1", 2, 0, 10, "a".repeat(64))
        assertTrue(drafts.put(StoredDraft(id, "note-1", "quote text", "my note")))
        assertEquals("my note", drafts.get(id)?.note)
    }

    @Test fun wholeLessonExplainsWithParagraphNumberedCitations() = runBlocking {
        val library = TextbookLibrary(root.resolve("library"))
        val pdfPath = root.resolve("lesson.pdf")
        PDDocument().use { pdf ->
            val p1 = PDPage(); pdf.addPage(p1)
            PDPageContentStream(pdf, p1).use { out ->
                out.beginText(); out.setFont(PDType1Font.HELVETICA, 18f); out.newLineAtOffset(50f, 710f); out.showText("Chapter 1 Energy")
                out.newLineAtOffset(0f, -48f); out.setFont(PDType1Font.HELVETICA, 11f); out.showText("Sunlight starts leaf photosynthesis.")
                out.endText()
            }
            val p2 = PDPage(); pdf.addPage(p2)
            PDPageContentStream(pdf, p2).use { out ->
                out.beginText(); out.setFont(PDType1Font.HELVETICA, 11f); out.newLineAtOffset(50f, 710f)
                out.showText("Chloroplasts capture energy during daytime.")
                out.newLineAtOffset(0f, -100f)
                out.showText("Sugars store that chemical energy for growth.")
                out.endText()
            }
            pdf.save(pdfPath.toFile())
        }
        val pdfBook = library.importBook(pdfPath)
        val whole = pdfBook.lessons.first()
        val bodyBlocks = whole.blocks.filterNot { it.heading }
        val secondOnPage2 = bodyBlocks.last()
        assertEquals(2, secondOnPage2.page)

        val txtPath = root.resolve("notes.txt")
        Files.write(txtPath, "# 1 Energy\n\nAlpha process.\n\nBeta storage.".toByteArray(Charsets.UTF_8))
        val txtBook = library.importBook(txtPath)
        val txtSecond = StudyCoachCourse.blocks(txtBook.id, txtBook.pages).filterNot { it.heading }.last()

        val capturedRequests = mutableListOf<String>()
        val token = "t".repeat(43)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/v1/pair" -> jsonReply(200, """{"deviceId":"android-test","token":"$token","role":"friend"}""")
                    "/v1/public/status" -> jsonReply(200, """{"ok":true}""")
                    "/v1/library/explain" -> {
                        val payload = request.body.readUtf8()
                        capturedRequests += payload
                        val parsed = Json.parseToJsonElement(payload).jsonObject
                        val lastBlock = parsed.getValue("blocks").jsonArray.last().jsonObject
                        val lastId = lastBlock.getValue("id").jsonPrimitive.content
                        val lastPage = lastId.substringAfter(":p").substringBefore(':').toInt()
                        jsonReply(200, """{"text":"全节围绕能量捕获和储存展开。【S3】","sources":[{"docId":"$lastId","pdfPage":$lastPage,"anchor":"S3"}],"elapsedMs":15}""")
                    }
                    else -> jsonReply(404, "{}")
                }
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.port}"
            AndroidBookConnection.bind(context, baseUrl, KeystoreCredentials(context, baseUrl))
            val explainer = BookExplainer(library)
            assertTrue(explainer.pair(baseUrl, "123456"))

            val support = PairedBookLearningSupport(
                library,
                sendLesson = { blocks, pages -> explainer.run { load(); explainLesson(blocks, pages) } },
                send = { block, question -> explainer.run { load(); ask(block, question) } },
            )
            support.allow(pdfBook.id)
            val reply = support.explain(BookExplanationRequest(pdfBook.id, whole.id, whole.blocks))
            assertTrue(reply is BookLearningReply.Text)
            val textReply = reply as BookLearningReply.Text
            assertEquals("全节围绕能量捕获和储存展开。", textReply.text)
            assertEquals(1, textReply.sources.size)

            val pdfLabel = bookSourceLabel(textReply.sources.single(), pdfBook)
            assertTrue(pdfLabel, pdfLabel.startsWith("第 2 页 · 第 2 段 · “"))
            val txtLabel = bookSourceLabel(BookPassage.from(txtBook, txtSecond), txtBook)
            assertTrue(txtLabel, txtLabel.startsWith("本章第 2 段 · “"))

            val wire = Json.parseToJsonElement(capturedRequests.single()).jsonObject
            assertEquals("lesson", wire.getValue("kind").jsonPrimitive.content)
            assertEquals(bodyBlocks.map { it.id }, wire.getValue("blocks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        } finally {
            server.shutdown()
        }
    }

    @Test fun revocation401And403AndLateResponseParity() = runBlocking {
        val tok1 = "1".repeat(43)
        val tok2 = "2".repeat(43)
        val tok3 = "3".repeat(43)
        val nextPairToken = AtomicInteger(0)
        val tokens = listOf(tok1, tok2, tok3)
        val explainStatus = AtomicInteger(403)
        val revokedTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val holdLateCheck = CountDownLatch(1)
        val lateCheckReached = CountDownLatch(1)
        val gateLateCheck = AtomicBoolean(false)

        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/v1/pair" -> {
                        val tok = tokens[nextPairToken.getAndIncrement().coerceAtMost(tokens.lastIndex)]
                        jsonReply(200, """{"deviceId":"android-rev","token":"$tok","role":"friend"}""")
                    }
                    "/v1/public/status" -> {
                        val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
                        if (gateLateCheck.get()) {
                            lateCheckReached.countDown()
                            holdLateCheck.await(10, TimeUnit.SECONDS)
                        }
                        val code = if (bearer in revokedTokens) 401 else 200
                        jsonReply(code, "{}")
                    }
                    "/v1/library/explain" -> {
                        val code = explainStatus.get()
                        when (code) {
                            403 -> jsonReply(403, """{"error":"这段原文没有访问权限。"}""")
                            503 -> jsonReply(503, """{"error":"讲解服务这会儿排满了，请稍后再试。","code":"unavailable"}""")
                            else -> jsonReply(code, """{"error":"unauthorized"}""")
                        }
                    }
                    else -> jsonReply(404, "{}")
                }
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.port}"
            AndroidBookConnection.bind(context, baseUrl, KeystoreCredentials(context, baseUrl))
            val library = TextbookLibrary(root.resolve("library-rev"))
            val txtPath = root.resolve("rev.txt")
            Files.write(txtPath, "# 1 Topic\n\nFirst body paragraph for revocation test.".toByteArray(Charsets.UTF_8))
            val book = library.importBook(txtPath)
            ExplainConsent(library.directory).allow(book.id)
            val block = book.lessons.first().blocks.first { !it.heading }

            val explainer = BookExplainer(library)
            explainer.load()
            val unpaired = explainer.explain(block, listOf(block)) as ExplainResult.Refused
            assertEquals(ExplainTrouble.Unpaired, unpaired.trouble)
            assertEquals(ExplainTrouble.UNPAIRED, unpaired.message)

            assertTrue(explainer.pair(baseUrl, "123456"))

            // 503 busy preserves identity and stays retryable
            explainStatus.set(503)
            val busy = explainer.explain(block, listOf(block)) as ExplainResult.Refused
            assertEquals(ExplainTrouble.Busy, busy.trouble)
            assertNotNull(explainer.identity)

            // 403 when /v1/public/status == 200 is NOT revocation
            explainStatus.set(403)
            val forbidden = explainer.explain(block, listOf(block)) as ExplainResult.Refused
            assertEquals(ExplainTrouble.Other, forbidden.trouble)
            assertEquals("这段原文没有访问权限。", forbidden.message)
            assertNotNull(explainer.identity)

            // 403 when /v1/public/status == 401 revokes pairing and clears old credential
            revokedTokens += tok1
            val revoked403 = explainer.explain(block, listOf(block)) as ExplainResult.Refused
            assertEquals(ExplainTrouble.Revoked, revoked403.trouble)
            assertEquals(ExplainTrouble.REVOKED_LOGIN, revoked403.message)
            assertNull(explainer.identity)
            assertTrue(explainer.revoked)

            // Re-pair with tok2; startup load() checks /v1/public/status -> 401 revokes immediately
            assertTrue(explainer.pair(baseUrl, "654321"))
            revokedTokens += tok2
            val fresh = BookExplainer(library)
            fresh.load()
            assertNull(fresh.identity)
            assertTrue(fresh.revoked)

            // Late 401 response race: re-pairing via another instance must NOT be wiped by delayed 401
            assertTrue(explainer.pair(baseUrl, "111222"))
            val oldIdentity = checkNotNull(explainer.identity)
            assertEquals(tok3, oldIdentity.credentials.token)

            explainStatus.set(403)
            revokedTokens += tok3
            gateLateCheck.set(true)
            val inFlight = async(Dispatchers.IO) { explainer.explain(block, listOf(block)) }
            assertTrue(lateCheckReached.await(10, TimeUnit.SECONDS))

            // Simulate a newer credential saved concurrently while old check is in flight
            val replacementCred = PairedCredentials("android-rev", "4".repeat(43), "friend")
            AndroidBookConnection.bind(context, baseUrl, KeystoreCredentials(context, baseUrl))
            KeystoreCredentials(context, baseUrl).save(replacementCred)
            gateLateCheck.set(false)
            holdLateCheck.countDown()

            val lateResult = inFlight.await() as ExplainResult.Refused
            assertEquals(ExplainTrouble.Other, lateResult.trouble)
            val observer = BookExplainer(library)
            observer.load()
            assertEquals(replacementCred.token, observer.identity?.credentials?.token)
            assertFalse(observer.revoked)
            assertNull(AndroidBookIdentity(baseUrl, oldIdentity.credentials).takeIf { it.credentials.token == observer.identity?.credentials?.token })
        } finally {
            holdLateCheck.countDown()
            server.shutdown()
        }
    }

    @Test fun trailingSlashBindNormalizesCredentialStoreConsistently() = runBlocking {
        val token = "s".repeat(43)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/v1/pair" -> jsonReply(200, """{"deviceId":"slash-dev","token":"$token","role":"friend"}""")
                    "/v1/public/status" -> jsonReply(200, "{}")
                    else -> jsonReply(404, "{}")
                }
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.port}"
            val store = KeystoreCredentials(context, "$baseUrl/")
            AndroidBookConnection.bind(context, "$baseUrl/", store)
            val explainer = BookExplainer()
            assertTrue(explainer.pair(baseUrl, "123456"))
            assertEquals(baseUrl, explainer.identity?.serverUrl)

            val loaded = AndroidBookConnection.load("$baseUrl/")
            assertEquals(token, loaded.first?.credentials?.token)
            assertFalse(loaded.second)
        } finally {
            server.shutdown()
        }
    }

    private fun jsonReply(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private object TestAndroidKeyStoreProvider {
        private val keys = ConcurrentHashMap<String, SecretKey>()
        private val provider = object : Provider("AndroidKeyStore", 1.0, "Robolectric test AndroidKeyStore") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStoreSpi::class.java.name)
                put("KeyGenerator.AES", TestKeyGeneratorSpi::class.java.name)
            }
        }

        fun install() {
            keys.clear()
            Security.removeProvider("AndroidKeyStore")
            Security.insertProviderAt(provider, 1)
        }

        fun uninstall() {
            Security.removeProvider("AndroidKeyStore")
            keys.clear()
        }

        class TestKeyStoreSpi : KeyStoreSpi() {
            override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
            override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
            override fun engineGetCertificate(alias: String?): Certificate? = null
            override fun engineGetCreationDate(alias: String?): Date? = null
            override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
                if (key is SecretKey) keys[alias] = key
            }
            override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) {
                keys[alias] = SecretKeySpec(key, "AES")
            }
            override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
            override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
            override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
            override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
            override fun engineSize(): Int = keys.size
            override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
            override fun engineIsCertificateEntry(alias: String?): Boolean = false
            override fun engineGetCertificateAlias(cert: Certificate?): String? = null
            override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
            override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        }

        class TestKeyGeneratorSpi : KeyGeneratorSpi() {
            private var alias: String = "default"
            private var sizeBits: Int = 256
            private var random = SecureRandom()

            override fun engineInit(random: SecureRandom) {
                this.random = random
            }

            override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom) {
                this.random = random
                if (params is KeyGenParameterSpec) {
                    alias = params.keystoreAlias
                    sizeBits = params.keySize.takeIf { it > 0 } ?: 256
                }
            }

            override fun engineInit(keysize: Int, random: SecureRandom) {
                this.random = random
                this.sizeBits = keysize
            }

            override fun engineGenerateKey(): SecretKey {
                val bytes = ByteArray(sizeBits / 8)
                random.nextBytes(bytes)
                return SecretKeySpec(bytes, "AES").also { keys[alias] = it }
            }
        }
    }
}
