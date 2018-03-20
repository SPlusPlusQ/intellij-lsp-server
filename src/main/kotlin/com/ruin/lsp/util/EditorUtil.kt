package com.ruin.lsp.util

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.DiffFragment
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.diff.Diff
import com.ruin.lsp.commands.document.find.offsetToPosition
import com.ruin.lsp.model.LogPrintWriter
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import java.util.*

/**
 * Convenience. Sometimes <code>PsiFile.findElementAt()</code>
 *  isn't enough. This will never return null.
 * @throws IllegalArgumentException if it couldn't find an element
 */
fun ensureTargetElement(editor: Editor): PsiElement =
    findTargetElement(editor) ?: throw IllegalArgumentException("No element under the cursor")

fun findTargetElement(editor: Editor): PsiElement? =
    TargetElementUtil
        .findTargetElement(editor,
            TargetElementUtil.getInstance().allAccepted)

private val LOG = Logger.getInstance("#com.ruin.lsp.util.EditorUtil")

/**
 * Creates, uses, then releases an editor.
 *
 * For some reason the Disposer doesn't release editor instances, so this is just working around the resulting
 * boilerplate.
 */
fun withEditor(context: Disposable, file: PsiFile, position: Position = Position(0, 0), callback: (Editor) -> Unit) {
    val editor = createEditor(context, file, position)

    try {
        callback(editor)
    } catch (e: Exception) {
        LOG.error("Exception during editor callback: " + e
            + e.stackTrace.asList().joinToString("\n") { it.toString() }
        )
    } finally {
        val editorFactory = EditorFactory.getInstance()
        editorFactory.releaseEditor(editor)
    }
}

fun differenceFromAction(file: PsiFile, callback: (Editor, PsiFile) -> Unit): List<TextEdit>? {
    val copy = createFileCopy(file)
    withEditor(Disposer.newDisposable(), copy, Position(0, 0)) { editor ->
        callback(editor, copy)
    }
    val oldDoc = getDocument(file) ?: return null
    val newDoc = getDocument(copy) ?: return null
    return textEditFromDocs(oldDoc, newDoc)
}

fun DiffFragment.toTextEdit(oldDoc: Document, newDoc: Document): TextEdit {
    val start = offsetToPosition(oldDoc, this.startOffset1)
    val end = offsetToPosition(oldDoc, this.endOffset1)
    val text = newDoc.getText(TextRange(this.startOffset2, this.endOffset2))
    return TextEdit(Range(start, end), text)
}

fun textEditFromDocs(oldDoc: Document, newDoc: Document): List<TextEdit> {
    val indicator = ProgressManager.getInstance().progressIndicator ?: DumbProgressIndicator.INSTANCE
    val changes = ComparisonManager.getInstance().compareChars(oldDoc.text, newDoc.text, ComparisonPolicy.DEFAULT, indicator)

    return changes.map {
        it.toTextEdit(oldDoc, newDoc)
    }
}
