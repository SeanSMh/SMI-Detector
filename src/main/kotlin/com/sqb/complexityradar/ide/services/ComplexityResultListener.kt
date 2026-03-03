package com.sqb.complexityradar.ide.services

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

fun interface ComplexityResultListener {
    fun resultsUpdated(files: Collection<VirtualFile>)

    companion object {
        val TOPIC: Topic<ComplexityResultListener> =
            Topic.create("Complexity Radar Results Updated", ComplexityResultListener::class.java)
    }
}
