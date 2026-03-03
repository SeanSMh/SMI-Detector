package com.sqb.complexityradar.ide.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService

class ComplexityRadarStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ComplexityRadarProjectService>().start()
    }
}
