package com.coach.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.client.PairedCredentials
import com.coach.domain.json
import com.coach.domain.str
import com.coach.ui.v7.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** The public listener accepts only excerpts from the friend's own imported material. */
@Composable
internal fun FriendQuestion(url: String, credentials: PairedCredentials, revoked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf("") }
    var digest by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readNBytes(64 * 1024 + 1) }
                }
                require(bytes.size <= 64 * 1024)
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                require(text.isNotBlank())
                book = text
                digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                answer = ""
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { answer = "请选择 64 KB 以内的 UTF-8 文本文件。" }
            finally { busy = false }
        }
    }
    var start by remember(book) { mutableIntStateOf(0) }
    val excerpt = book.drop(start).take(2000)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选段提问", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(enabled = !busy, onClick = { choose.launch(arrayOf("text/plain")) }) { Text("打开文本文件") }
        if (book.isNotEmpty()) {
            Text(excerpt)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && start > 0, onClick = { start -= 2000; answer = "" }) { Text("上一段") }
                TextButton(enabled = !busy && start + 2000 < book.length, onClick = { start += 2000; answer = "" }) { Text("下一段") }
            }
            OutlinedTextField(question, { question = it.take(300) }, enabled = !busy, label = { Text("想问什么？") })
            PrimaryButton(enabled = !busy && question.isNotBlank() && excerpt.isNotBlank(), onClick = {
                busy = true
                answer = ""
                scope.launch {
                    val http = CourseHttp(url, credentials.deviceId, credentials.token, "library")
                    try {
                        val id = "$digest:p1:$start"
                        val body = json("requestId" to UUID.randomUUID().toString().replace("-", ""), "kind" to "ask",
                            "question" to question.trim(), "selection" to excerpt.trim(),
                            "blocks" to JsonArray(listOf(json("id" to id, "text" to excerpt))),
                            "localSource" to json("schema" to 1, "book" to digest, "format" to "txt", "title" to "文本",
                                "consented" to true, "pages" to JsonArray(listOf(json("number" to 1, "length" to book.length,
                                    "ocr" to false, "sha256" to digest)))))
                        val reply = http.explainLibrary(body)
                        if (reply.statusCode == 401) revoked()
                        else {
                            val result = reply.body
                            val sources = result?.get("sources") as? JsonArray
                            answer = if (!sources.isNullOrEmpty() && sources.all {
                                (it as? JsonObject)?.str("docId") == id && it.jsonObject["pdfPage"]?.jsonPrimitive?.intOrNull == 1
                            }) result.str("text") else reply.error ?: "这次没有得到可核对的讲解。"
                        }
                    } finally { http.close(); busy = false }
                }
            }) { Text(if (busy) "请稍候…" else "发送本段和问题") }
        }
        if (answer.isNotBlank()) Text(answer)
    }
}
