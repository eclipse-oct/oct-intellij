package org.typefox.oct.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.project.Project
import org.typefox.oct.ClientTextSelection
import org.typefox.oct.messageHandlers.OCTMessageHandler
import org.typefox.oct.TextDocumentInsert


class EditorDocumentListener(private val octService: OCTMessageHandler.OCTService,
                             private val path: String,
                             private val project: Project):
    DocumentListener {

    var sendUpdates = true

    private var isSyncing = false

    override fun documentChanged(event: DocumentEvent) {
        if(sendUpdates) {
            val offset = event.offset
            octService.updateDocument(
                path, arrayOf(
                    TextDocumentInsert(
                        offset,
                        offset + event.oldFragment.length,
                        event.newFragment.toString()
                    )
                )
            )
        }
        if(!isSyncing) {
            syncDocument(event)
        }
    }

    private fun syncDocument(event: DocumentEvent) {
        isSyncing = true
        octService.getDocumentContent(path).thenAccept { fileContent ->
            val content = if(fileContent != null) String(fileContent.content) else null
            if (content != null && !content.contentEquals(event.document.charsSequence)) {
                WriteCommandAction.runWriteCommandAction(project) {
                    sendUpdates = false
                    event.document.setText(content)
                    sendUpdates = true
                    isSyncing = false
                }
            } else {
                isSyncing = false
            }
        }
    }
}

class EditorCaretListener(private val octService: OCTMessageHandler.OCTService, private val path: String) :
    CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
        val caret = event.caret!!
        val selectionStart = caret.selectionStart
        val selectionEnd = caret.selectionEnd
        octService.updateTextSelection(
            path, arrayOf(
                ClientTextSelection(
                    "", // by default its always oneself
                    selectionStart,
                    selectionEnd,
                    selectionEnd < selectionStart
                )
            )
        )
    }
}

class EditorSelectionListener(private val octService: OCTMessageHandler.OCTService, private val path: String) :
    SelectionListener {
    override fun selectionChanged(event: SelectionEvent) {
        val selectionModel = event.editor.selectionModel
        val selectionStart = selectionModel.selectionStart
        val selectionEnd = selectionModel.selectionEnd
        octService.updateTextSelection(
            path, arrayOf(
                ClientTextSelection(
                    "", // by default its always oneself
                    selectionStart,
                    selectionEnd,
                    selectionEnd < selectionStart
                )
            )
        )
    }
}
