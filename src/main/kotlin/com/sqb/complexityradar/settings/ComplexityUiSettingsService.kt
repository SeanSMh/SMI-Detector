package com.sqb.complexityradar.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sqb.complexityradar.core.model.UiSettingsState

@State(name = "ComplexityRadarUiSettings", storages = [Storage("complexity-radar-ui.xml")])
@Service(Service.Level.PROJECT)
class ComplexityUiSettingsService : PersistentStateComponent<UiSettingsState> {
    private var state = UiSettingsState()

    override fun getState(): UiSettingsState = state

    override fun loadState(state: UiSettingsState) {
        this.state = state
    }

    fun update(transform: (UiSettingsState) -> Unit) {
        transform(state)
    }

    companion object {
        fun getInstance(project: Project): ComplexityUiSettingsService = project.service()
    }
}
