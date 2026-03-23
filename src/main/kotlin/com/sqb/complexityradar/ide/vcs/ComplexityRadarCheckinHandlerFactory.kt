package com.bril.code_radar.ide.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.bril.code_radar.ide.services.ComplexityRadarProjectService

class ComplexityRadarCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext,
    ): CheckinHandler =
        object : CheckinHandler() {
            override fun checkinSuccessful() {
                val project = panel.project
                val files = panel.virtualFiles.toList()
                if (files.isEmpty()) {
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) {
                        return@invokeLater
                    }
                    ComplexityRadarProjectService.getInstance(project).scheduleFiles(files)
                }
            }
        }
}
