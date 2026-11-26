package com.coach.desktop.books

import java.util.UUID
import java.sql.Connection

internal sealed interface StructureEdit {
    data class BodyStart(val page: Int?) : StructureEdit
    data class Rename(val id: String, val title: String) : StructureEdit
    data class MergeWithPrevious(val id: String) : StructureEdit
    data class Split(val id: String?, val page: Int, val title: String, val offset: Int = 0) : StructureEdit
}

internal fun Textbook.applyStructureEdit(edit: StructureEdit): Textbook {
    val detected = structure ?: BookStructurePlanner.plan(id, title, format, pages)
    val nodes = detected.tocNodes.toMutableList()
    val chapters = nodes.filter { it.kind == TocNodeKind.CHAPTER }
    fun chapter(key: String) = chapters.single { it.id == key }
    fun checkedTitle(value: String) = value.trim().also { require(it.isNotEmpty() && it.length <= 160) }
    fun replace(node: BookTocNode) { nodes[nodes.indexOfFirst { it.id == node.id }] = node }
    var bodyPage = correctedBodyPage
    when (edit) {
        is StructureEdit.BodyStart -> {
            require(edit.page == null || page(edit.page) != null)
            bodyPage = edit.page
        }
        is StructureEdit.Rename -> {
            val node = chapter(edit.id)
            replace(node.copy(title = checkedTitle(edit.title), numberLabel = null, source = HeadingSource.MANUAL))
        }
        is StructureEdit.MergeWithPrevious -> {
            val node = chapter(edit.id)
            val previous = chapters[chapters.indexOf(node) - 1]
            require(previous.parentId == node.parentId)
            nodes.remove(node)
            nodes.replaceAll { if (it.parentId == node.id) it.copy(parentId = previous.id) else it }
        }
        is StructureEdit.Split -> {
            val old = edit.id?.let(::chapter)
            val text = requireNotNull(page(edit.page)).text
            require(edit.offset == 0 || edit.offset in 1 until text.length)
            require(if (old == null) chapters.isEmpty() else
                (edit.page > old.startPage || edit.page == old.startPage && edit.offset > old.startOffset) &&
                    BookChapterResolver.chapterForPage(this, edit.page, edit.offset)?.tocNodeId == old.id)
            fun atOrAfter(node: BookTocNode) = node.startPage > edit.page ||
                node.startPage == edit.page && node.startOffset >= edit.offset
            val newChapter = BookTocNode(UUID.randomUUID().toString(), old?.parentId, 0,
                old?.level ?: 1, TocNodeKind.CHAPTER, null, checkedTitle(edit.title),
                edit.page, old?.endPage ?: pages.last().number, source = HeadingSource.MANUAL, startOffset = edit.offset)
            val index = nodes.indexOfFirst { atOrAfter(it) && (old == null || it.ordinal > old.ordinal) }
            nodes.add(if (index < 0) nodes.size else index, newChapter)
            nodes.replaceAll { child ->
                if (child.kind in setOf(TocNodeKind.SECTION, TocNodeKind.SUBSECTION) &&
                    child.parentId == old?.id && atOrAfter(child) && child.startPage <= newChapter.endPage
                ) child.copy(parentId = newChapter.id) else child
            }
        }
    }
    val updated = detected.copy(
        version = BookStructure.CURRENT_VERSION,
        tocNodes = if (edit is StructureEdit.BodyStart) detected.tocNodes
            else BookTocWaterfall.withEndPages(nodes, pages.last().number),
        correctedStart = bodyPage,
        source = if (edit is StructureEdit.BodyStart) detected.source else HeadingSource.MANUAL.name,
        editRevision = UUID.randomUUID().toString(),
        modelAttempted = true,
    )
    val blocks = StudyCoachCourse.blocks(id, pages, snapSentences = true)
    val roles = pages.associate { it.number to updated.pageRole(it.number) }
    val (chunks, sections, _) = BookTocWaterfall.buildStudyChunks(id, title, pages, roles,
        updated.tocNodes, blocks.groupBy { it.page }, bodyPage?.let { BookPageAnchor(it) } ?: updated.nativeBodyStart, edited = true)
    val final = updated.copy(sections = sections, studyChunks = chunks)
    final.resolvedLessons = final.toLessons(id, pages, blocks)
    val anchor = lessons.getOrNull(lessonIndex)?.blocks?.firstOrNull()
    val index = when {
        finished -> final.resolvedLessons.size
        anchor == null -> 0
        else -> final.resolvedLessons.indexOfFirst { lesson ->
            lesson.blocks.last().let { it.page > anchor.page || it.page == anchor.page && it.end > anchor.start }
        }.let { if (it < 0) final.resolvedLessons.lastIndex.coerceAtLeast(0) else it }
    }
    return copy(structure = final, correctedBodyPage = bodyPage, lessonIndex = index)
}

/** A reply generated before a correction may arrive after the old cache was cleared. */
internal fun Connection.cacheForStructure(expected: Textbook?, write: () -> Unit) {
    if (expected == null) {
        write()
        return
    }
    autoCommit = false
    try {
        val revision = prepareStatement("SELECT json_extract(payload,'$.editRevision') FROM book_structures WHERE book=?").use {
            it.setString(1, expected.id)
            it.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }
        if (revision == expected.structure?.editRevision) write()
        commit()
    } catch (error: Exception) {
        rollback()
        throw error
    } finally {
        autoCommit = true
    }
}
