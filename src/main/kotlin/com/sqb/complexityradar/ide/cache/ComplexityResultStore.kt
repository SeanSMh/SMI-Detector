package com.sqb.complexityradar.ide.cache

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.ScoreDigest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ComplexityResultStore(
    private val projectBasePath: String?,
) {
    private val results = ConcurrentHashMap<String, ComplexityResult>()

    fun put(
        file: VirtualFile,
        result: ComplexityResult,
        digest: ScoreDigest,
    ) {
        results[file.url] = result
        file.putUserData(DIGEST_KEY, digest)
        runCatching {
            writeToDisk(result)
        }.onFailure { error ->
            LOG.warn("Failed to persist complexity cache for ${result.filePath}", error)
        }
    }

    fun get(file: VirtualFile): ComplexityResult? = results[file.url]

    fun getDigest(file: VirtualFile): ScoreDigest? = file.getUserData(DIGEST_KEY)

    fun allResults(): List<ComplexityResult> = results.values.sortedByDescending { it.priority }

    fun resultsFor(files: Collection<VirtualFile>): List<ComplexityResult> = files.mapNotNull { results[it.url] }

    fun invalidate(file: VirtualFile) {
        results.remove(file.url)
        file.putUserData(DIGEST_KEY, null)
        runCatching {
            deleteFromDisk(file.url)
        }.onFailure { error ->
            LOG.warn("Failed to delete complexity cache for ${file.path}", error)
        }
    }

    fun clearAll() {
        results.keys
            .mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it) }
            .forEach { file -> file.putUserData(DIGEST_KEY, null) }
        results.clear()
        cacheDir()?.let { dir ->
            if (Files.exists(dir)) {
                Files.walk(dir).use { stream ->
                    stream
                        .sorted(Comparator.reverseOrder())
                        .forEach { path ->
                            if (path != dir) {
                                Files.deleteIfExists(path)
                            }
                        }
                }
            }
        }
    }

    fun restoreFromDisk() {
        val dir = cacheDir() ?: return
        if (!Files.exists(dir)) {
            return
        }
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.forEach { path ->
                runCatching {
                    val result = JsonSupport.mapper.readValue(Files.readString(path), ComplexityResult::class.java)
                    results[result.fileUrl] = result
                    VirtualFileManager.getInstance().findFileByUrl(result.fileUrl)?.putUserData(DIGEST_KEY, digestFrom(result))
                }.onFailure { error ->
                    LOG.warn("Failed to restore complexity cache from $path", error)
                }
            }
        }
    }

    private fun writeToDisk(result: ComplexityResult) {
        val dir = cacheDir() ?: return
        Files.createDirectories(dir)
        val path = dir.resolve(fileNameFor(result.fileUrl))
        Files.writeString(path, JsonSupport.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    }

    private fun deleteFromDisk(fileUrl: String) {
        val dir = cacheDir() ?: return
        Files.deleteIfExists(dir.resolve(fileNameFor(fileUrl)))
    }

    private fun cacheDir(): Path? {
        val base = projectBasePath ?: return null
        return Path.of(base).resolve(".idea").resolve("complexity-radar").resolve("cache-v2.1")
    }

    private fun fileNameFor(fileUrl: String): String = "${sha256(fileUrl)}.json"

    private fun digestFrom(result: ComplexityResult): ScoreDigest =
        ScoreDigest(
            score = result.score,
            severity = result.severity,
            mode = result.mode,
            topContributions = result.contributions.take(3).map { "${it.type.displayName} ${(it.weightedScore * 100).toInt()}" },
            effectiveLoc = result.effectiveLoc,
            maxDepth = maxOf(result.maxBlockDepth, result.maxLambdaDepth),
            domainCount = result.domainCount,
            hotspotCount = result.hotspots.size,
        )

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val LOG = Logger.getInstance(ComplexityResultStore::class.java)
        val DIGEST_KEY: Key<ScoreDigest> = Key.create("com.sqb.complexityradar.digest")
    }
}
