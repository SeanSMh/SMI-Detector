package com.bril.code_radar.ide.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.bril.code_radar.ide.services.ComplexityRadarProjectService

class ComplexityRadarStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ComplexityRadarProjectService>().start()
    }
}
