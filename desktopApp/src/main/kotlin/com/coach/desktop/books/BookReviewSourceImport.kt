package com.coach.desktop.books

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-owner receipt, selected explicitly in the review screen. This is transport, not approval:
 * approvalId references the owner's existing decision; importing never approves/generates questions.
 * The owner supplies all identities and asserts that usedQuestions is its complete posed set.
 * Ordinary textbooks without this separate receipt retain their honest not-ready state.
 *
 * v1 envelope: format="coach-book-review-source/v1", bookSha256, lessonId, mode,
 * approvalId, revision, posedSetComplete=true, input (the persisted source JSON, or null to withdraw).
 */
internal fun TextbookLibrary.importReviewVariantSource(review: LocalBookReview, path: Path) {
    val receipt = try {
        val bytes = Files.newInputStream(path).use { it.readNBytes(1_048_577) }
        require(bytes.size in 1..1_048_576)
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (failure: Exception) {
        throw IllegalArgumentException("无法读取变式来源文件，请选择完整的来源登记 JSON（不超过 1 MB）。", failure)
    }
    val update = try {
        require(receipt.getValue("format").jsonPrimitive.content == "coach-book-review-source/v1")
        require(receipt.getValue("bookSha256").jsonPrimitive.content == review.book &&
            receipt.getValue("lessonId").jsonPrimitive.content == review.lesson &&
            receipt.getValue("mode").jsonPrimitive.content == review.mode.name) {
            "来源文件与当前教材、课时或题型不一致。"
        }
        require(receipt.getValue("posedSetComplete").jsonPrimitive.boolean) { "来源须提供完整的已做题集合。" }
        val input = receipt.getValue("input").let { if (it == JsonNull) null else BookReviewSourceJson.decode(it.jsonObject) }
        BookReviewSourceUpdate(receipt.getValue("approvalId").jsonPrimitive.content,
            receipt.getValue("revision").jsonPrimitive.long, input)
    } catch (failure: Exception) {
        throw IllegalArgumentException("来源登记未保存：文件缺少完整身份或与当前题不一致。", failure)
    }
    // Existing transaction re-reads/validates the original, revision and posed facts before committing.
    updateReviewVariantSource(review, update)
}
