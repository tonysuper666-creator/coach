package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.math.floor
import kotlin.math.round

private val knownFormats = Format.entries.map { it.name }.toSet()
private val strictPurposes = setOf("CLOSED_BOOK","UNSEEN_VARIANT","TIMED","FULL_MOCK")
private val timedPurposes = setOf("TIMED","FULL_MOCK")

private data class Attempt(val posed: Event, var submitted: Event? = null, var grade: JsonObject? = null,
    var reviewed: Boolean = false, var uncertain: Boolean = false, var voided: Boolean = false,
    var hinted: Boolean = false, var assisted: Boolean = false, val firstKps: MutableSet<String> = mutableSetOf(),
    var fresh: Boolean = false, var day: Long = 0, var answerTime: Long = 0, var correctCounted: Boolean = false,
    val scheduled: MutableSet<String> = mutableSetOf()) {
    val id get() = posed.payload.str("attemptId")
    val kps get() = posed.payload.strings("kpIds")
    val question get() = posed.payload.str("questionId")
    val seq get() = submitted?.seq ?: Long.MAX_VALUE
    val ratio get() = grade?.let { it.num("score").toDouble() / it.num("max", 1).coerceAtLeast(1) } ?: 0.0
    val pass get() = ratio >= 0.8
}

/** Ordered ledger fold. Arrival order is irrelevant: seq is authoritative, including late reviews.
 * One invocation is scoped to a course. Unknown facts are preserved by the caller and flagged here.
 */
fun project(input: Iterable<Event>, zone: String = "Europe/London", upcasters: Upcasters = Upcasters()): Projection {
    val events = input.distinctBy { it.id }.sortedWith(compareBy<Event> { it.seq }.thenBy { it.id })
    require(events.map { it.courseId }.distinct().size <= 1) { "Project one course at a time" }
    require(events.zipWithNext().none { (a,b) -> a.seq == b.seq && a.id != b.id }) { "Conflicting ledger seq" }
    val taughtConceptIds = sortedSetOf<String>()
    val explainedKpIds = sortedSetOf<String>()
    val attempts = mutableMapOf<String, Attempt>()
    val byQuestion = mutableMapOf<String, MutableList<Attempt>>()
    val keys = mutableMapOf<String, String>()
    val firstQuestion = mutableSetOf<String>()
    val fingerprintTimes = mutableMapOf<String, MutableList<Long>>()
    val mastery = mutableMapOf<String, Mastery>()
    val windows = mutableMapOf<String, MutableList<Attempt>>()
    val solids = mutableMapOf<String, MutableMap<String, Attempt>>()
    val solidDays = mutableMapOf<String, MutableMap<Long, Int>>()
    val solidRatios = mutableMapOf<String, Double>()
    val solidSums = mutableMapOf<String, Double>()
    val solidConstructed = mutableMapOf<String, Int>()
    val review = mutableMapOf<String, Review>()
    val journal = mutableMapOf<Long, Journal>()
    val firstReview = mutableMapOf<Pair<String,Long>, String>()
    val outline = mutableListOf<Kp>()
    val overrides = mutableMapOf<String, String>()
    val legacy = mutableSetOf<String>()
    val pending = mutableMapOf<String, Attempt>()
    val orphans = mutableListOf<String>()
    val unsupported = mutableSetOf<String>()
    val weights = mutableMapOf<String, Int>()
    var current: String? = null
    var examDay: Long? = null
    var previousTime: Long? = null
    var previousDay: Long? = null
    val seenIdem = mutableSetOf<String>()

    fun updateJournal(day: Long, block: (Journal) -> Journal) { journal[day] = block(journal[day] ?: Journal()) }
    fun cap(day: Long, interval: Int): Int {
        val days = examDay?.minus(day) ?: return interval
        return if (days > 0) interval.coerceAtMost(((days - 1) / 2).toInt().coerceAtLeast(1)) else interval
    }
    fun due(day: Long, interval: Int): Long {
        val exam=examDay
        return if(exam!=null && exam>day) minOf(day+interval,exam-1) else day+interval
    }
    fun schedule(kp: String, day: Long, result: String) {
        val old = review[kp] ?: return
        if (old.lastReviewDay == day) return
        val interval = when(result) { "PASS" -> floor(old.intervalDays * old.ease).toInt().coerceAtLeast(1); "NEEDS_WORK" -> (old.intervalDays / 2).coerceAtLeast(1); else -> 1 }
        val ease = round((when(result) { "PASS" -> (old.ease + .1).coerceAtMost(3.0); "NEEDS_WORK" -> (old.ease - .15).coerceAtLeast(1.3); else -> (old.ease - .2).coerceAtLeast(1.3) }) * 1000) / 1000
        val n = cap(day, interval)
        review[kp] = old.copy(dueDay = due(day,n), intervalDays = n, ease = ease,
            lapses = old.lapses + if (result == "FAIL") 1 else 0, lastResult = result, lastReviewDay = day)
        updateJournal(day) { j -> when(result) { "PASS" -> j.copy(reviewPass = j.reviewPass + 1); "NEEDS_WORK" -> j.copy(reviewNeedsWork = j.reviewNeedsWork + 1); else -> j.copy(reviewFail = j.reviewFail + 1) } }
        mastery[kp] = (mastery[kp] ?: Mastery()).copy(needsRepair = result != "PASS")
    }
    fun graded(a: Attempt): Boolean = a.grade != null && !a.uncertain &&
        (a.grade!!.str("status",a.grade!!.str("grader")) == "EXACT" || a.reviewed)
    fun eligible(a: Attempt, kp: String): Boolean = a.submitted != null && !a.voided && !a.hinted && kp in a.firstKps && a.fresh &&
        a.posed.payload.num("policyVersion", 1) == 1L && a.posed.payload.str("format") in knownFormats && a.posed.payload.str("origin") != "LEGACY" &&
        keys[a.question] == "VERIFIED" && graded(a)
    fun solidContext(a: Attempt): Boolean {
        val purpose = a.posed.payload.str("purpose")
        val mc = a.posed.payload.str("format") == "SINGLE_CHOICE"
        return !a.assisted && purpose in strictPurposes &&
            (!mc || purpose in timedPurposes) &&
            !(a.submitted!!.payload.flag("offline") && purpose in timedPurposes)
    }
    fun refresh(kp: String, event: Event, changed: Attempt? = null) {
        val old = mastery[kp] ?: Mastery()
        val list = windows.getOrPut(kp) { mutableListOf() }
        if (changed != null) {
            val idx = list.binarySearch { it.seq.compareTo(changed.seq) }
            val was = idx >= 0
            val now = eligible(changed,kp)
            if (was && !now) list.removeAt(idx)
            if (!was && now) list.add(-idx - 1, changed)
            val sm = solids.getOrPut(kp) { mutableMapOf() }
            val days = solidDays.getOrPut(kp) { mutableMapOf() }
            val before = sm.remove(changed.id)
            if (before != null) {
                // Saved ratio rather than mutable grade is needed when reviews revise scores.
                val saved = solidRatios.remove("$kp:${changed.id}") ?: 0.0
                solidSums[kp] = (solidSums[kp] ?: 0.0) - saved
                days[before.day] = (days[before.day] ?: 1) - 1
                if (days[before.day] == 0) days.remove(before.day)
                if (before.posed.payload.str("format") != "SINGLE_CHOICE") solidConstructed[kp] = (solidConstructed[kp] ?: 1) - 1
            }
            if (now && solidContext(changed) && changed.pass) {
                sm[changed.id] = changed
                solidRatios["$kp:${changed.id}"] = changed.ratio
                solidSums[kp] = (solidSums[kp] ?: 0.0) + changed.ratio
                days[changed.day] = (days[changed.day] ?: 0) + 1
                if (changed.posed.payload.str("format") != "SINGLE_CHOICE") solidConstructed[kp] = (solidConstructed[kp] ?: 0) + 1
            }
        }
        val count = solids[kp]?.size ?: 0
        val progress = SolidProgress(count, solidConstructed[kp] ?: 0, solidDays[kp]?.size ?: 0,
            if (count == 0) 0.0 else (solidSums[kp] ?: 0.0) / count)
        val latestContext = list.lastOrNull { solidContext(it) }
        val solid = progress.count >= 2 && progress.constructed >= 1 && progress.days >= 2 && progress.average + 1e-12 >= .85 && latestContext?.pass == true
        var support = old.support
        var solidLatch=old.solidLatch
        var revokedSeq = old.revokedAtSeq
        var reason = old.revokedReason
        if (support.isNotEmpty() && support.count { id -> attempts[id]?.let { eligible(it,kp) && it.pass } == true } < 3) {
            support = emptyList(); revokedSeq = event.seq
            reason = "${event.type}:${event.payload.str("reason", "evidence_invalid")}:${changed?.id.orEmpty()}"
        }
        if(solidLatch.isNotEmpty()) {
            val live=solidLatch.mapNotNull { attempts[it] }.filter { eligible(it,kp) && solidContext(it) && it.pass }
            if(live.size<2 || live.none { it.posed.payload.str("format")!="SINGLE_CHOICE" } || live.map { it.day }.distinct().size<2 || live.map { it.ratio }.average()+1e-12<.85) {
                solidLatch=emptyList();revokedSeq=event.seq
                reason="${event.type}:${event.payload.str("reason","evidence_invalid")}:${changed?.id.orEmpty()}"
            }
        }
        if(solid && solidLatch.isEmpty()) solidLatch=solids[kp].orEmpty().values.sortedBy { it.seq }.map { it.id }
        val tail = list.takeLast(3)
        if (support.isEmpty() && tail.size == 3 && tail.all { it.pass } && tail.map { it.posed.payload.str("fingerprint") }.distinct().size == 3) support = tail.map { it.id }
        val passed = support.isNotEmpty() || solidLatch.isNotEmpty() || solid || overrides[kp] == "MARK_PASSED" || kp in legacy
        val passedBy = when { kp in legacy -> "LEGACY"; overrides[kp] == "MARK_PASSED" -> "LEARNER"; passed -> "SYSTEM"; else -> null }
        mastery[kp] = Mastery(passed, passedBy,
            if (!old.passed && passed) event.seq else if (passed) old.passedAtSeq else null,
            solid, old.formerlySolid || (old.solid && !solid),
            old.needsRepair || (changed != null && changed.submitted != null && graded(changed) && !changed.pass && old.passed),
            tail.map { it.id }, support, progress, revokedSeq, reason, solidLatch)
        val day = logicalDay(event.occurredAt.coerceAtMost(event.recordedAt), zone)
        if (!old.passed && passed) {
            val n = cap(day, if (solid) 3 else 1)
            review.putIfAbsent(kp, Review(due(day,n), n))
            updateJournal(day) { it.copy(newlyPassed = (it.newlyPassed + kp).distinct()) }
        }
        if (!old.solid && solid) {
            updateJournal(day) { it.copy(newlySolid = (it.newlySolid + kp).distinct()) }
            val n = cap(day, 3)
            review[kp]?.let { review[kp] = it.copy(dueDay = due(day,n), intervalDays = n) }
        }
        if (!passed) review.remove(kp)
        if (changed != null && graded(changed) && !changed.voided && passed) {
            val isReview = changed.posed.payload.flag("review")
            if (isReview && firstReview[kp to changed.day] == changed.id && changed.scheduled.add(kp)) {
                schedule(kp, changed.day, if (changed.pass) "PASS" else if (changed.ratio >= .5) "NEEDS_WORK" else "FAIL")
            } else if (!isReview && !changed.pass) {
                review[kp]?.let { review[kp] = it.copy(dueDay = minOf(it.dueDay, due(changed.day,1))) }
            }
        }
    }
    fun refreshAttempt(a: Attempt, e: Event) {
        for (kp in a.kps) refresh(kp, e, a)
        val correct = graded(a) && !a.voided && a.pass
        if (a.submitted != null && correct != a.correctCounted) {
            updateJournal(a.day) { it.copy(correct = it.correct + if (correct) 1 else -1) }
            a.correctCounted = correct
        }
    }
    fun firstness(a: Attempt) {
        a.firstKps.clear()
        if(a.voided) { a.fresh=false;return }
        a.firstKps.addAll(a.kps.filter { "$it:${a.question}" !in firstQuestion })
        a.fresh=fingerprintTimes[a.posed.payload.str("fingerprint")].orEmpty().none { kotlin.math.abs(a.answerTime-it)<14L*86400000 }
        for(kp in a.kps) firstQuestion.add("$kp:${a.question}")
        fingerprintTimes.getOrPut(a.posed.payload.str("fingerprint")) { mutableListOf() }.add(a.answerTime)
    }
    for (raw in events) {
        if (!seenIdem.add(raw.idemKey)) continue
        val e = upcasters.upcast(raw)
        if (e == null) { unsupported.add(raw.type); continue }
        val p = e.payload
        val day = logicalDay(e.occurredAt.coerceAtMost(e.recordedAt), zone)
        if (e.actor != "SYSTEM") {
            val delta = previousTime?.let { e.occurredAt - it } ?: 0
            if (previousDay == day && delta in 1 until 600_000) updateJournal(day) { it.copy(minutes = it.minutes + delta / 60000.0) }
            previousTime = e.occurredAt; previousDay = day
        }
        if (e.type != "journal_summarized") journal[day]?.let { if (it.summaryFromSeq != null && e.seq > it.summaryFromSeq) journal[day] = it.copy(summaryStale = true) }
        when (e.type) {
            "outline_revised" -> {
                for (v in (p["ops"] as? JsonArray).orEmpty()) {
                    val o = v.jsonObject; val id = o.str("kpId", o.str("id"))
                    val i = outline.indexOfFirst { it.id == id }
                    when(o.str("op", "add")) {
                        "add", "setPrereq", "retire" -> {
                            val old = outline.getOrNull(i) ?: Kp(id)
                            val value = old.copy(prereqs = if (o.containsKey("prereqs")) o.strings("prereqs") else old.prereqs, retired = old.retired || o.str("op") == "retire")
                            if (i < 0) outline.add(value) else outline[i] = value
                        }
                        "move" -> if (i >= 0) { val kp = outline.removeAt(i); outline.add(o.num("index").toInt().coerceIn(0,outline.size), kp) }
                    }
                    mastery.putIfAbsent(id, Mastery())
                }
            }
            "kp_opened" -> {
                if (p.str("via") !in setOf("REVIEW", "QUICK")) current = p.str("kpId")
                updateJournal(day) { it.copy(opened = (it.opened + p.str("kpId")).distinct()) }
            }
            "explanation_delivered" -> {
                taughtConceptIds.addAll(p.strings("conceptIds"))
                explainedKpIds.add(p.str("kpId"))
                updateJournal(day) { it.copy(delivered = (it.delivered + p.str("kpId")).distinct()) }
            }
            "question_posed", "legacy_attempt" -> {
                if(p.num("policyVersion",1) != 1L) unsupported.add("policy:${p.num("policyVersion")}")
                val a = Attempt(if (e.type == "legacy_attempt") e.copy(payload = JsonObject(p + ("origin" to JsonPrimitive("LEGACY")))) else e)
                attempts[a.id] = a; byQuestion.getOrPut(a.question) { mutableListOf() }.add(a)
                keys.putIfAbsent(a.question, p.str("keyState", "UNREVIEWED"))
                pending[e.threadId ?: a.kps.firstOrNull().orEmpty()] = a
            }
            "followup_asked" -> {
                attempts[p.str("pendingAttemptId")]?.takeIf { it.submitted == null }?.assisted = true
                updateJournal(day) { it.copy(followups = it.followups + 1) }
            }
            "hint_revealed" -> attempts[p.str("attemptId")]?.takeIf { it.submitted == null }?.hinted = true
            "answer_submitted" -> attempts[p.str("attemptId")]?.let { a ->
                if (a.submitted == null) {
                    a.submitted = e
                    val at = e.occurredAt.coerceIn(a.posed.payload.num("posedAt",a.posed.recordedAt).coerceAtMost(e.recordedAt), e.recordedAt)
                    a.day = logicalDay(at, zone);a.answerTime=at
                    // First answers and fingerprint history are derived from submitted seq, never pose order.
                    firstness(a)
                    for(kp in a.kps) if(a.posed.payload.flag("review")) firstReview.putIfAbsent(kp to a.day,a.id)
                    pending.entries.removeAll { it.value.id == a.id }
                    updateJournal(a.day) { it.copy(answers = it.answers + 1) }
                    refreshAttempt(a, e)
                }
            }
            "attempt_graded" -> attempts[p.str("attemptId")]?.let { a ->
                require(p.num("max") > 0 && p.num("score") in 0..p.num("max")) { "Invalid integer score" }
                a.grade = p; a.reviewed = p.str("status") == "VERIFIED"; a.uncertain = false
                refreshAttempt(a, e)
            }
            "grade_reviewed" -> attempts[p.str("attemptId")]?.let { a ->
                a.reviewed = p.str("decision") in setOf("CONFIRM","REVISE"); a.uncertain = !a.reviewed
                (p["revised"] as? JsonObject)?.let { revised ->
                    require(revised.num("max") > 0 && revised.num("score") in 0..revised.num("max"))
                    a.grade = JsonObject(a.grade.orEmpty() + revised)
                }
                refreshAttempt(a, e)
            }
            "attempt_voided" -> attempts[p.str("attemptId")]?.let { a ->
                a.voided = true; pending.entries.removeAll { it.value.id == a.id }; refreshAttempt(a,e)
                // Rare validity changes rebuild firstness; the normal append path stays O(1) per attempt.
                firstQuestion.clear();fingerprintTimes.clear()
                for(other in attempts.values.filter { it.submitted!=null }.sortedBy { it.seq }) {
                    val oldFirst=other.firstKps.toSet();val oldFresh=other.fresh
                    firstness(other)
                    if(oldFirst!=other.firstKps || oldFresh!=other.fresh) refreshAttempt(other,e)
                }
            }
            "question_key_changed" -> {
                keys[p.str("questionId")] = p.str("keyState")
                for (a in byQuestion[p.str("questionId")].orEmpty()) refreshAttempt(a,e)
            }
            "kp_overridden" -> { overrides[p.str("kpId")] = p.str("kind"); refresh(p.str("kpId"),e) }
            "override_cleared" -> { overrides.remove(p.str("kpId")); refresh(p.str("kpId"),e) }
            "legacy_passed" -> { legacy.add(p.str("kpId")); refresh(p.str("kpId"),e) }
            "legacy_schedule" -> if (p.str("kpId") in legacy) review[p.str("kpId")] = Review(p.num("dueDay"),p.num("interval",1).toInt(),p["ease"]?.jsonPrimitive?.doubleOrNull ?: 2.3,p.num("lapses").toInt())
            "review_completed" -> {
                val kp = p.str("kpId")
                if (mastery[kp]?.passed == true) schedule(kp, day, p.str("result"))
            }
            "review_snoozed" -> review[p.str("kpId")]?.let { val due = p.num("untilDay"); review[p.str("kpId")] = it.copy(dueDay = due(day,cap(day,(due-day).toInt().coerceAtLeast(1)))) }
            "exam_set" -> {
                examDay = if (p.containsKey("examDay")) p.num("examDay") else logicalDay(p.num("examAt"),zone)
                for ((kp,r) in review.toMap()) { val n = cap(day,(r.dueDay-day).toInt().coerceAtLeast(1)); review[kp] = r.copy(dueDay = minOf(r.dueDay,due(day,n)), intervalDays = cap(day,r.intervalDays)) }
            }
            "exam_weights_set" -> { weights.clear(); for ((kp,v) in (p["weights"] as? JsonObject).orEmpty()) weights[kp] = v.jsonPrimitive.int }
            "note_saved" -> updateJournal(day) { it.copy(notes = it.notes + 1) }
            "journal_edited" -> updateJournal(p.num("day",day)) { it.copy(rev = p.num("rev")) }
            "journal_summarized" -> updateJournal(p.num("day",day)) { it.copy(summaryFromSeq = p.num("fromSeq"),summaryStale = events.any { x -> x.seq > p.num("fromSeq") && x.seq < e.seq && logicalDay(x.occurredAt.coerceAtMost(x.recordedAt),zone) == p.num("day",day) }) }
            "answer_orphaned" -> { orphans.add(p.str("attemptId")); updateJournal(day) { it.copy(orphaned = it.orphaned+1) } }
        }
    }
    val next = outline.firstOrNull { !it.retired && mastery[it.id]?.passed != true && overrides[it.id] !in setOf("SKIP","DEFER") && it.prereqs.all { x -> mastery[x]?.passed == true } }
        ?: outline.firstOrNull { !it.retired && mastery[it.id]?.passed != true && overrides[it.id] !in setOf("SKIP","DEFER") }
    val pendingTarget = pending.values.lastOrNull { it.posed.payload.str("purpose") != "RECALL" && !it.posed.payload.flag("review") && !it.posed.payload.flag("offlinePack") }?.kps?.firstOrNull()
    val target = pendingTarget ?: current?.takeIf { mastery[it]?.passed != true } ?: next?.id
    return Projection(lastSeq = events.lastOrNull()?.seq ?: 0, position = Position(current,pending.mapValues { it.value.id }.ordered(),target),
        mastery = mastery.ordered(), review = review.ordered(), journal = journal.ordered(),
        taughtConceptIds = taughtConceptIds, explainedKpIds = explainedKpIds,
        coverage = Coverage(weights.filterKeys { mastery[it]?.passed == true }.values.sum(), weights.filterKeys { mastery[it]?.solid == true }.values.sum(),weights.values.sum()),
        orphanedAttempts = orphans, unsupportedTypes = unsupported.sorted(), requiresServerSnapshot = unsupported.isNotEmpty())
}

private fun <K : Comparable<K>, V> Map<K,V>.ordered(): Map<K,V> = entries.sortedBy { it.key }.associate { it.key to it.value }
