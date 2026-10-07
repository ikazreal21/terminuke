package com.terminuke.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.terminuke.app.ssh.RemoteShell

class RemoteTerminalBridge(
    context: Context,
    shell: RemoteShell,
    scrollbackRows: Int = 5_000,
    onClosed: () -> Unit,
) {
    val terminalView = TerminalView(context, null)
    private val sessionClient = RemoteSessionClient(context, { terminalView }, onClosed)
    val session = TerminalSession("", "", emptyArray(), emptyArray(), scrollbackRows, sessionClient)

    init {
        terminalView.setTextSize(14)
        terminalView.setTerminalViewClient(RemoteViewClient(context, terminalView) { columns, rows -> shell.resize(columns, rows) })
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        session.initializeRemote(shell.input, shell.output, 80, 24, 9, 18, scrollbackRows)
        terminalView.attachSession(session)
    }

    fun send(bytes: ByteArray) = session.write(bytes, 0, bytes.size)

    fun close() = session.finishIfRunning()
}

private class RemoteSessionClient(
    private val context: Context,
    private val view: () -> TerminalView,
    private val onClosed: () -> Unit,
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        view().onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = onClosed()
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal selection", text))
        Toast.makeText(context, "Copied. Clear clipboard after using sensitive text.", Toast.LENGTH_LONG).show()
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = 0
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

private class RemoteViewClient(
    context: Context,
    private val view: TerminalView,
    private val onSizeChanged: (Int, Int) -> Unit,
) : TerminalViewClient {
    private val inputMethod = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    override fun onScale(scale: Float) = scale.coerceIn(0.75f, 2.0f)
    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {
        view.mEmulator?.let { onSizeChanged(it.mColumns, it.mRows) }
    }
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}
