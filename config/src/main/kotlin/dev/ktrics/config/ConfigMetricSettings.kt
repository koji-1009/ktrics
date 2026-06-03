package dev.ktrics.config

import dev.ktrics.ir.Lang
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.MetricSettings
import dev.ktrics.metric.Thresholds

/**
 * [MetricSettings] backed by `ktrics.yaml`. Resolution order, most-specific first:
 * a metric's per-language config override → its both-language config override → the catalogue's
 * per-language default → the catalogue's both-language default. `enabled` lets a config turn an
 * off-by-default metric (Halstead, MI) on, or disable any metric (`metric: false`).
 */
class ConfigMetricSettings(private val config: KtricsConfig) : MetricSettings {
    override fun isEnabled(def: MetricDef): Boolean = config.metrics[def.id]?.enabled ?: def.enabledByDefault

    override fun thresholds(
        def: MetricDef,
        lang: Lang,
    ): Thresholds {
        val base = def.thresholdsFor(lang) // catalogue both + catalogue per-language
        val entry = config.metrics[def.id] ?: return base
        val perLang =
            when (lang) {
                Lang.JAVA -> entry.java
                Lang.KOTLIN -> entry.kotlin
            }
        return Thresholds(
            warning = perLang?.warning ?: entry.warning ?: base.warning,
            error = perLang?.error ?: entry.error ?: base.error,
        )
    }
}
