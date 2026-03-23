package com.bril.code_radar.ide.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import com.bril.code_radar.settings.ComplexityUiSettingsService
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class ComplexityRadarConfigurable(
    private val project: Project,
) : SearchableConfigurable {
    private val settingsService = ComplexityUiSettingsService.getInstance(project)

    private var panel: JPanel? = null
    private var projectViewBox: JCheckBox? = null
    private var bannerBox: JCheckBox? = null
    private var gutterBox: JCheckBox? = null
    private var externalCommandField: JTextField? = null

    override fun getId(): String = "com.bril.code_radar.settings"

    override fun getDisplayName(): String = "Complexity Radar"

    override fun createComponent(): JComponent {
        if (panel == null) {
            val saved = settingsService.state
            projectViewBox = JCheckBox("Show Project View decorations", saved.showProjectViewDecoration)
            bannerBox = JCheckBox("Show editor banner", saved.showEditorBanner)
            gutterBox = JCheckBox("Show gutter hotspots", saved.showGutterIcons)
            externalCommandField = JTextField(saved.externalCommand)
            panel =
                JPanel(GridBagLayout()).apply {
                    val gbc =
                        GridBagConstraints().apply {
                            anchor = GridBagConstraints.WEST
                            fill = GridBagConstraints.HORIZONTAL
                            insets = Insets(4, 4, 4, 4)
                            weightx = 1.0
                            gridx = 0
                            gridy = 0
                        }
                    add(projectViewBox, gbc)
                    gbc.gridy += 1
                    add(bannerBox, gbc)
                    gbc.gridy += 1
                    add(gutterBox, gbc)
                    gbc.gridy += 1
                    add(JLabel("External command template"), gbc)
                    gbc.gridy += 1
                    add(externalCommandField, gbc)
                }
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        val saved = settingsService.state
        return projectViewBox?.isSelected != saved.showProjectViewDecoration ||
            bannerBox?.isSelected != saved.showEditorBanner ||
            gutterBox?.isSelected != saved.showGutterIcons ||
            externalCommandField?.text.orEmpty() != saved.externalCommand
    }

    override fun apply() {
        settingsService.update { state ->
            state.showProjectViewDecoration = projectViewBox?.isSelected ?: state.showProjectViewDecoration
            state.showEditorBanner = bannerBox?.isSelected ?: state.showEditorBanner
            state.showGutterIcons = gutterBox?.isSelected ?: state.showGutterIcons
            state.externalCommand = externalCommandField?.text.orEmpty()
        }
        EditorNotifications.getInstance(project).updateAllNotifications()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun reset() {
        val saved = settingsService.state
        projectViewBox?.isSelected = saved.showProjectViewDecoration
        bannerBox?.isSelected = saved.showEditorBanner
        gutterBox?.isSelected = saved.showGutterIcons
        externalCommandField?.text = saved.externalCommand
    }

    override fun disposeUIResources() {
        panel = null
        projectViewBox = null
        bannerBox = null
        gutterBox = null
        externalCommandField = null
    }
}
