package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.*

/** Consumed by the real mainline screen; an outline is navigation, never a coverage manifest. */
data class LearningRuntimeState(val context: LadderRuntimeContext) {
    private val scopeConsistent: Boolean get() {
        val points = context.scopePoints ?: return false
        return context.scopeKnown && !context.scopeVersion.isNullOrBlank() &&
            (context.syntheticOnly || (context.scopeEvidence.isNotEmpty() && context.scopeEvidence.all { it.isNotBlank() })) &&
            points.map { it.id }.let { ids -> ids.all { it.isNotBlank() } && ids.distinct().size == ids.size &&
                ids.toSet() == context.applicableKpIds?.toSet() } &&
            points.all { point -> point.capabilityIds?.let { ids -> ids.all { it.isNotBlank() } && ids.distinct().size == ids.size } != false }
    }
    val scopePoints: List<LadderScopePoint> get() = if (scopeConsistent) context.scopePoints.orEmpty() else emptyList()
    val declaredKpTotal: Int? get() = context.scopePoints?.size?.takeIf { scopeConsistent }
    val declaredCapabilityTotal: Int? get() {
        val pairs = context.capabilityScope
        if (context.syntheticOnly && !context.scopeVersion.isNullOrBlank() && pairs != null &&
            pairs.all { it.kpId.isNotBlank() && it.capabilityId.isNotBlank() } &&
            pairs.map { it.kpId to it.capabilityId }.distinct().size == pairs.size) return pairs.size
        return if (scopeConsistent && scopePoints.all { it.capabilityIds != null }) scopePoints.sumOf { it.capabilityIds.orEmpty().size } else null
    }
    val completeDenominator: Int? get() = declaredKpTotal?.takeIf { context.scopeVerified && !context.syntheticOnly }
    val scopeMessage: String get() = when {
        context.syntheticOnly && declaredKpTotal != null -> "合成学习范围：$declaredKpTotal 个考点；" +
            (declaredCapabilityTotal?.let { "$it 项能力。" } ?: "能力范围待核。") + "不计掌握。"
        context.syntheticOnly && declaredCapabilityTotal != null -> "合成能力范围：$declaredCapabilityTotal 项；考点范围待核。不计掌握。"
        context.syntheticOnly -> "合成示例，不计掌握；正式学习范围仍待核。"
        completeDenominator == null -> "全部适用知识点清单待核，暂不计算完整覆盖率。"
        else -> "已核学习范围：$completeDenominator 个知识点；" +
            (declaredCapabilityTotal?.let { "$it 项能力。" } ?: "能力范围待核。")
    }
    companion object {
        fun fromWeek(body: JsonObject?): LearningRuntimeState = LearningRuntimeState(
            (body?.get("runtime") as? JsonObject)?.let {
                runCatching { LedgerJson.decodeFromJsonElement<LadderRuntimeContext>(it) }.getOrNull()
            } ?: LadderRuntimeContext())
    }
}

/** IDs are an association, not the model answer itself. No approved reader endpoint exists yet. */
data class LadderEssayState(val modelAnswerId: String?, val rubricPointIds: List<String>?, val malformed: Boolean) {
    fun identity(answer: JsonObject): MainlineEssayIdentity? = if (malformed) null else
        MainlineEssayIdentity(answer.str("itemId"), answer.str("attemptId"), modelAnswerId.orEmpty(), rubricPointIds.orEmpty()).takeIf { it.valid }
    val message: String get() = if (malformed) "论述关联资料待核，暂不能查看示范全文与评分点。"
        else if (modelAnswerId == null || rubricPointIds.isNullOrEmpty()) "论述示范全文或评分点仍待核。"
        else "已关联论述示范与评分点；全文读取尚未接通，暂不能查看。"
    companion object {
        fun fromAnswer(answer: JsonObject?): LadderEssayState? {
            val raw = answer?.get("essay")?.takeUnless { it == JsonNull } ?: return null
            val obj = raw as? JsonObject ?: return LadderEssayState(null, null, true)
            val model = obj["model_answer_id"]
            val ids = obj["rubric_point_ids"]
            val malformed = (model != null && model != JsonNull && (model !is JsonPrimitive || !model.isString || model.content.isBlank())) ||
                (ids != null && ids != JsonNull && (ids !is JsonArray || ids.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }))
            return LadderEssayState((model as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                (ids as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, malformed)
        }
    }
}
