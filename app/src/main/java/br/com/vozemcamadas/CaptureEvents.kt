package br.com.vozemcamadas

import java.io.File

object CaptureEvents {
    interface Listener {
        fun onCaptureStatus(state: String, message: String)
        fun onCaptureFile(file: File, mode: String)
    }

    data class PendingFile(val file: File, val mode: String)

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var pendingFile: PendingFile? = null

    fun attach(value: Listener) {
        listener = value
        pendingFile?.let {
            pendingFile = null
            value.onCaptureFile(it.file, it.mode)
        }
    }

    fun detach(value: Listener) {
        if (listener === value) listener = null
    }

    fun status(state: String, message: String) {
        listener?.onCaptureStatus(state, message)
    }

    fun file(file: File, mode: String) {
        val active = listener
        if (active != null) active.onCaptureFile(file, mode)
        else pendingFile = PendingFile(file, mode)
    }
}
