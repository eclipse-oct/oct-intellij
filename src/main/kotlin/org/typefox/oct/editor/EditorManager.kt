package org.typefox.oct.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import org.typefox.oct.ClientTextSelection
import org.typefox.oct.OCTSessionService
import org.typefox.oct.messageHandlers.OCTMessageHandler
import org.typefox.oct.TextDocumentInsert
import java.awt.Color
import java.awt.Graphics
import java.io.FileNotFoundException
import kotlin.io.path.Path
import kotlin.io.path.pathString

class EditorManager(
    private val octService: OCTMessageHandler.OCTService,
    val project: Project,
    private val sessionFileResolver: (String) -> VirtualFile?
) :
    EditorFactoryListener {
    private val editors: MutableMap<String, Editor> = mutableMapOf()
    private val cursorDecorations: MutableMap<String, Array<PeerCursorDecoration>> = mutableMapOf()
    private val documentListeners: MutableMap<String, EditorDocumentListener> = mutableMapOf()

    var followingPeerId: String? = null

    init {
        FileEditorManager.getInstance(project).allEditors.forEach {
            val editor: Editor? = when (it) {
                is TextEditor ->  it.editor
                is Editor -> it
                else -> {
                    println("Warn: editor is not a TextEditorWithPreview or Editor")
                    null
                }
            }
            if (editor != null) {
                registerEditor(editor)
            }
        }
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        registerEditor(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val path = octPathFromEditor(event.editor)
        clearPeerDecorations(path, event.editor)
        editors.remove(path)
        event.editor.document.removeDocumentListener(documentListeners.remove(path)!!)
    }

    private fun registerEditor(editor: Editor) {
        if(editor.virtualFile == null) {
            return
        }
        val path = octPathFromEditor(editor)

        editors[path] = editor

        octService.openDocument("text", path, editor.document.text)

        documentListeners[path] = EditorDocumentListener(octService, path, editor.project!!)
        editor.document.addDocumentListener(documentListeners[path]!!)
        editor.caretModel.addCaretListener(EditorCaretListener(octService, path))
        editor.selectionModel.addSelectionListener(EditorSelectionListener(octService, path))
    }

    fun updateTextSelection(path: String, selections: Array<ClientTextSelection>) {
        val editor = editors[path]

        for (selection in selections) {
            if (selection.peer == followingPeerId) {
                invokeLater {
                    val editorManager = FileEditorManager.getInstance(this.project)
                    val file = sessionFileResolver(path)
                    if(file == null) {
                        return@invokeLater
                    }
                    editorManager.openFile(file, true)
                    editors[path]?.scrollingModel?.scrollTo(
                        LogicalPosition(selection.start, selection.end ?: selection.start), ScrollType.CENTER)
                }
                break
            }
        }

        if(editor != null) {
            ApplicationManager.getApplication().invokeAndWait {
                clearPeerDecorations(path, editor)
                cursorDecorations[path] = Array(selections.size) { idx ->
                    val selection = selections[idx]
                    createPeerCursor(selection, editor)
                }
            }
        }
    }

    private fun createPeerCursor(selection: ClientTextSelection, editor: Editor): PeerCursorDecoration {
        val color = service<OCTSessionService>().currentCollaborationInstances[editor.project]!!
            .peerColors.getColor(selection.peer)
        val textLength = editor.document.textLength
        val start = selection.start.coerceIn(0, textLength)
        val end = (selection.end ?: selection.start).coerceIn(0, textLength)

        val selectionStart = minOf(start, end)
        val selectionEnd = maxOf(start, end)

        var textHighlighter: RangeHighlighter? = null
        if (selectionStart != selectionEnd) {
            val highlightColor = Color(color.red, color.green, color.blue, 50)
            textHighlighter = editor.markupModel.addRangeHighlighter(
                selectionStart,
                selectionEnd,
                HighlighterLayer.CARET_ROW + 1,
                TextAttributes(null, JBColor(highlightColor, highlightColor), null, null, 0),
                HighlighterTargetArea.EXACT_RANGE
            )
        }

        val cursorHighlighter = editor.markupModel.addRangeHighlighter(
            start,
            start,
            HighlighterLayer.CARET_ROW + 2,
            null,
            HighlighterTargetArea.EXACT_RANGE
        )
        cursorHighlighter.customRenderer = PeerCaretHighlighterRenderer(color)

        return PeerCursorDecoration(cursorHighlighter, textHighlighter)
    }

    private fun clearPeerDecorations(path: String, editor: Editor) {
        cursorDecorations.remove(path)?.forEach { decoration ->
            editor.markupModel.removeHighlighter(decoration.cursorHighlighter)
            decoration.textHighlighter?.let(editor.markupModel::removeHighlighter)
        }
    }

    fun updateDocument(path: String, updates: Array<TextDocumentInsert>) {
        val virtualFile = findFileByRelativePath(path)
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, Throwable> {
            FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: throw IllegalStateException("Document for file $path not found")
        }

        WriteCommandAction.runWriteCommandAction(
            project
        ) {
            val listener =  documentListeners[path]
            listener?.sendUpdates = false
            try {
                for (update in updates) {
                    document.replaceString(
                        update.startOffset,
                       update.endOffset ?: update.startOffset,
                        update.text.replace("\r\n", "\n")
                    )
                }
            } finally {
                listener?.sendUpdates = true
            }
        }
    }

    private fun octPathFromEditor(editor: Editor): String {
        return editor.virtualFile.path.replace(
            Path(editor.project!!.basePath!!).parent.pathString.replace("\\", "/") + "/",
            ""
        )
    }

    fun followPeer(peerId: String) {
        followingPeerId = peerId
    }

    fun stopFollowing() {
        followingPeerId = null
    }

    fun guestOpenedEditor(path: String) {
        val document = FileDocumentManager.getInstance().getDocument(findFileByRelativePath(path))

        octService.openDocument("text", path, document!!.text)
    }

    private fun findFileByRelativePath(path: String): VirtualFile {
        return sessionFileResolver(path) ?: throw FileNotFoundException("File not found: $path")
    }
}

private data class PeerCursorDecoration(
    val cursorHighlighter: RangeHighlighter,
    val textHighlighter: RangeHighlighter?
)

private class PeerCaretHighlighterRenderer(private val color: Color) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        val offset = highlighter.startOffset.coerceIn(0, editor.document.textLength)
        val point = editor.offsetToXY(offset)
        val bottom = point.y + editor.lineHeight - 1

        g.color = color
        g.drawLine(point.x, point.y, point.x, bottom)
        g.drawLine(point.x + 1, point.y, point.x + 1, bottom)
    }
}

