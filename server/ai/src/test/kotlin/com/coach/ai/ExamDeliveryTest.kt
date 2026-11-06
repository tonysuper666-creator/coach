package com.coach.ai

import com.coach.core.ExamTextGuard
import com.coach.core.ExamTextIndex
import java.nio.file.Files
import kotlin.test.*

class ExamDeliveryTest {
    private val exams = listOf("Amber badgers carry delicate emerald feathers gently home.", "Ivory jaguars keep lemons near old purple quails.")
    private val index = ExamTextIndex(exams)

    @Test fun textMathAndReplacementShareOneProtectedPublication() {
        val delivery = ExamDelivery { ExamTextGuard(index) }
        val published = StringBuilder()
        fun push(segment: VisibleStream.Segment) {
            published.append(delivery.push(segment).joinToString("") { ExamDelivery.markdown(it) })
            assertFalse(index.overlaps(published.toString()))
        }
        push(VisibleStream.Segment.Text("Amber badgers carry delicate "))
        push(VisibleStream.Segment.Math("emerald feathers", false))
        push(VisibleStream.Segment.Text(" gently home."))
        published.append(delivery.finish().joinToString("") { ExamDelivery.markdown(it) })
        assertFalse(index.overlaps(published.toString()))
        assertTrue(delivery.blocked)
        val replacement = delivery.replace(exams[1]).joinToString("") { ExamDelivery.markdown(it) }
        assertFalse(index.overlaps(replacement))
        assertFalse(index.overlaps(replacement + delivery.finish().joinToString("") { ExamDelivery.markdown(it) }))
    }

    @Test fun ordinaryMathKeepsItsTypedRepresentation() {
        val delivery = ExamDelivery { ExamTextGuard(index) }
        val segments = delivery.push(VisibleStream.Segment.Math("x = 2", true)) + delivery.finish()
        assertTrue(segments.single() is VisibleStream.Segment.Math)
        assertEquals("x = 2", (segments.single() as VisibleStream.Segment.Math).tex)
    }

    @Test fun missingPrivateFilesProtectTheCourseAndPromptReferencesAreBounded() {
        val missing = ExamMaterials.load(Files.createTempDirectory("empty-exam"))
        assertFalse(missing.available)
        assertTrue(missing.guard("ECNM10119")!!.push("raw") .contains("解题思路"))
        val materials = ExamMaterials.fromTexts(exams, "Synthetic planning: essays 40 percent.", "Synthetic adjudications.")
        val context = materials.context("ECNM10119", "explain quails")
        assertTrue(context.contains("essays 40 percent"))
        assertTrue(context.contains("quails"))
        assertTrue(context.contains("fresh situations and numbers"))
        assertTrue(context.length < 10_000)
        assertEquals("", materials.context("another-course", "question"))
    }

    @Test fun everyInjectedPlanningDocumentIsProtected() {
        val planning = "Silver turtles use velvet wings across blue gardens."
        val rulings = "Quiet robots arrange bright stones beneath orange trees."
        val materials = ExamMaterials.fromTexts(exams, planning, rulings)
        val context = materials.context("ECNM10119", "teach")
        for (reference in exams + listOf(planning, rulings)) {
            assertTrue(context.contains(reference))
            val guard = materials.guard("ECNM10119")!!
            val published = reference.chunked(1).joinToString("") { guard.push(it) } + guard.finish()
            assertTrue(guard.blocked)
            assertFalse(materials.index!!.overlaps(published))
        }
    }

    @Test fun aBlockedMessageStaysBlockedAcrossSafeAndUnsafeRevisions() {
        val delivery = ExamDelivery { ExamTextGuard(index) }
        delivery.push(VisibleStream.Segment.Text(exams[0]))
        assertTrue(delivery.blocked)
        for (revision in listOf("A new unrelated explanation.", exams[1])) {
            val replacement = delivery.replace(revision).joinToString("") { ExamDelivery.markdown(it) }
            assertTrue(delivery.blocked)
            assertEquals(ExamTextGuard.REASONING, replacement)
            assertTrue(delivery.push(VisibleStream.Segment.Text("Later model output.")).isEmpty())
            assertTrue(delivery.finish().isEmpty())
        }
    }
}
