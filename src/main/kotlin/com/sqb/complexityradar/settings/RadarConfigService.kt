package com.sqb.complexityradar.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.sqb.complexityradar.core.model.RadarConfig
import com.sqb.complexityradar.core.model.RadarConfigDefaults
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class RadarConfigService(
    private val project: Project,
) {
    @Volatile
    private var rootConfig: RadarConfig = loadRootConfig()

    fun getConfig(file: VirtualFile? = null): RadarConfig {
        var config = rootConfig
        if (file == null || project.basePath.isNullOrBlank()) {
            return config
        }
        val basePath = Path.of(project.basePath!!)
        val moduleConfigs =
            ancestorConfigFiles(file)
                .filter { it != basePath.resolve("radar.yaml") }
                .sortedBy { it.nameCount }
        moduleConfigs.forEach { path ->
            config = loadConfigFrom(path, config)
        }
        return config
    }

    fun reload() {
        rootConfig = loadRootConfig()
    }

    fun findConfigFiles(): List<Path> = ancestorConfigFiles(null)

    private fun loadRootConfig(): RadarConfig {
        val basePath = project.basePath ?: return RadarConfigDefaults.defaultConfig
        return loadConfigFrom(Path.of(basePath).resolve("radar.yaml"), RadarConfigDefaults.defaultConfig)
    }

    private fun loadConfigFrom(
        path: Path,
        base: RadarConfig,
    ): RadarConfig {
        if (!Files.exists(path)) {
            return base
        }
        return runCatching {
            RadarConfigParser.parse(Files.readString(path), base)
        }.getOrElse { base }
    }

    private fun ancestorConfigFiles(file: VirtualFile?): List<Path> {
        val basePath = project.basePath ?: return emptyList()
        val root = Path.of(basePath)
        if (file == null) {
            return listOf(root.resolve("radar.yaml")).filter(Files::exists)
        }

        val result = mutableListOf<Path>()
        val currentFilePath = Path.of(file.path)
        var current: Path? = currentFilePath.parent
        while (current != null && current.startsWith(root)) {
            val config = current.resolve("radar.yaml")
            if (Files.exists(config)) {
                result.add(config)
            }
            current = current.parent
        }
        val rootConfig = root.resolve("radar.yaml")
        if (Files.exists(rootConfig) && rootConfig !in result) {
            result.add(rootConfig)
        }
        return result
    }

    companion object {
        fun getInstance(project: Project): RadarConfigService = project.service()
    }
}
