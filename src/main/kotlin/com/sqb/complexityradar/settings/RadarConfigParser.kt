package com.sqb.complexityradar.settings

import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.HotspotConfig
import com.sqb.complexityradar.core.model.ModeConfig
import com.sqb.complexityradar.core.model.MultiplierRule
import com.sqb.complexityradar.core.model.NormalizationConfig
import com.sqb.complexityradar.core.model.RadarConfig
import com.sqb.complexityradar.core.model.RadarConfigDefaults
import com.sqb.complexityradar.core.model.RulesConfig
import com.sqb.complexityradar.core.model.ScalePoint
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.core.model.SeverityRange
import org.yaml.snakeyaml.Yaml

object RadarConfigParser {
    @Suppress("UNCHECKED_CAST")
    fun parse(
        content: String,
        base: RadarConfig = RadarConfigDefaults.defaultConfig,
    ): RadarConfig {
        if (content.isBlank()) {
            return base
        }
        val root = Yaml().load<Any?>(content) as? Map<String, Any?> ?: return base
        val radar = (root["radar"] as? Map<String, Any?>) ?: root

        val version = radar["version"]?.toString() ?: base.version
        val thresholdsMap = radar["thresholds"] as? Map<String, Any?>
        val thresholds =
            if (thresholdsMap == null) {
                base.thresholds
            } else {
                base.thresholds.toMutableMap().apply {
                    thresholdsMap.forEach { (key, value) ->
                        val severity = parseSeverity(key) ?: return@forEach
                        val pair = (value as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: return@forEach
                        if (pair.size == 2) {
                            this[severity] = SeverityRange(pair[0], pair[1])
                        }
                    }
                }
            }

        val modeMap = radar["mode"] as? Map<String, Any?>
        val mode =
            if (modeMap == null) {
                base.mode
            } else {
                ModeConfig(
                    default = parseAnalyzeMode(modeMap["default"]?.toString()) ?: base.mode.default,
                    accurateOnOpenFile = (modeMap["accurate_on_open_file"] as? Boolean) ?: base.mode.accurateOnOpenFile,
                    accurateOnTopRedFiles = (modeMap["accurate_on_top_red_files"] as? Number)?.toInt() ?: base.mode.accurateOnTopRedFiles,
                )
            }

        val weightsMap = radar["weights"] as? Map<String, Any?>
        val weights =
            if (weightsMap == null) {
                base.weights
            } else {
                base.weights.toMutableMap().apply {
                    weightsMap.forEach { (key, value) ->
                        val factor = parseFactorType(key) ?: return@forEach
                        this[factor] = (value as? Number)?.toDouble() ?: this.getValue(factor)
                    }
                }
            }

        val normalizationMap = radar["normalization"] as? Map<String, Any?>
        val normalization =
            if (normalizationMap == null) {
                base.normalization
            } else {
                parseNormalization(normalizationMap, base.normalization)
            }

        val rulesMap = radar["rules"] as? Map<String, Any?>
        val rules =
            if (rulesMap == null) {
                base.rules
            } else {
                RulesConfig(
                    kotlinWhenSimpleWeight = (rulesMap["kotlin_when_simple_weight"] as? Number)?.toDouble() ?: base.rules.kotlinWhenSimpleWeight,
                )
            }

        val multipliers =
            ((radar["multipliers"] as? List<*>)?.mapNotNull { entry ->
                val map = entry as? Map<String, Any?> ?: return@mapNotNull null
                val onFactors =
                    (map["on_factors"] as? List<*>)?.mapNotNull { parseFactorType(it?.toString()) }?.toSet().orEmpty()
                val match = map["match"]?.toString() ?: return@mapNotNull null
                val value = (map["value"] as? Number)?.toDouble() ?: return@mapNotNull null
                MultiplierRule(match = match, value = value, onFactors = onFactors)
            })?.ifEmpty { null } ?: base.multipliers

        val exclusions =
            ((radar["exclusions"] as? List<*>)?.mapNotNull { it?.toString() }) ?: base.exclusions

        val hotspotMap = radar["hotspot"] as? Map<String, Any?>
        val hotspot =
            if (hotspotMap == null) {
                base.hotspot
            } else {
                HotspotConfig(
                    gutterThreshold = (hotspotMap["gutter_threshold"] as? Number)?.toInt() ?: base.hotspot.gutterThreshold,
                    maxGutterPerFile = (hotspotMap["max_gutter_per_file"] as? Number)?.toInt() ?: base.hotspot.maxGutterPerFile,
                    maxHotspotsPerFile = (hotspotMap["max_hotspots_per_file"] as? Number)?.toInt() ?: base.hotspot.maxHotspotsPerFile,
                )
            }

        return RadarConfig(
            version = version,
            thresholds = thresholds,
            mode = mode,
            weights = weights,
            normalization = normalization,
            rules = rules,
            multipliers = multipliers,
            exclusions = exclusions,
            hotspot = hotspot,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNormalization(
        map: Map<String, Any?>,
        base: NormalizationConfig,
    ): NormalizationConfig {
        val sizeMap = map["size"] as? Map<String, Any?>
        val nestingMap = map["nesting"] as? Map<String, Any?>
        val controlMap = map["control_flow"] as? Map<String, Any?>
        val domainMap = map["domain_coupling"] as? Map<String, Any?>
        val readabilityMap = map["readability"] as? Map<String, Any?>

        return NormalizationConfig(
            locPoints = parsePoints(sizeMap?.get("loc_points"), base.locPoints),
            statementPoints = parsePoints(sizeMap?.get("statement_points"), base.statementPoints),
            functionPoints = parsePoints(sizeMap?.get("function_points"), base.functionPoints),
            typePoints = parsePoints(sizeMap?.get("type_points"), base.typePoints),
            controlFlowPoints = parsePoints(controlMap?.get("points"), base.controlFlowPoints),
            nestingPenaltyPoints = parsePoints(nestingMap?.get("penalty_points"), base.nestingPenaltyPoints),
            domainCountPoints = parsePoints(domainMap?.get("count_points"), base.domainCountPoints),
            maxFunctionLocPoints = parsePoints(readabilityMap?.get("max_function_loc_points"), base.maxFunctionLocPoints),
            maxParamPoints = parsePoints(readabilityMap?.get("max_param_points"), base.maxParamPoints),
            smellPoints = parsePoints(readabilityMap?.get("smell_points"), base.smellPoints),
        )
    }

    private fun parsePoints(
        value: Any?,
        fallback: List<ScalePoint>,
    ): List<ScalePoint> {
        val rawPoints = value as? List<*> ?: return fallback
        val parsed =
            rawPoints.mapNotNull { item ->
                val pair = item as? List<*> ?: return@mapNotNull null
                if (pair.size != 2) {
                    return@mapNotNull null
                }
                val x = (pair[0] as? Number)?.toDouble() ?: return@mapNotNull null
                val y = (pair[1] as? Number)?.toDouble() ?: return@mapNotNull null
                ScalePoint(x, y)
            }
        return if (parsed.isEmpty()) fallback else parsed
    }

    private fun parseAnalyzeMode(raw: String?): AnalyzeMode? =
        when (raw?.trim()?.lowercase()) {
            "fast" -> AnalyzeMode.FAST
            "accurate" -> AnalyzeMode.ACCURATE
            else -> null
        }

    private fun parseSeverity(raw: String): Severity? =
        when (raw.trim().lowercase()) {
            "green" -> Severity.GREEN
            "yellow" -> Severity.YELLOW
            "orange" -> Severity.ORANGE
            "red" -> Severity.RED
            else -> null
        }

    private fun parseFactorType(raw: String?): FactorType? =
        when (raw?.trim()?.lowercase()) {
            "size" -> FactorType.SIZE
            "control_flow" -> FactorType.CONTROL_FLOW
            "nesting" -> FactorType.NESTING
            "domain_coupling" -> FactorType.DOMAIN_COUPLING
            "readability" -> FactorType.READABILITY
            else -> null
        }
}
