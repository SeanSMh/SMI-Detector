package com.sqb.complexityradar.ide.services

import com.intellij.util.messages.Topic

data class ResultUpdateBatch(
    val fileUrls: List<String>,
)

fun interface ComplexityResultListener {
    fun resultsUpdated(batch: ResultUpdateBatch)

    companion object {
        val TOPIC: Topic<ComplexityResultListener> =
            Topic.create("Complexity Radar Results Updated", ComplexityResultListener::class.java)
    }
}
