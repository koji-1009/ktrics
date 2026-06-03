package dev.ktrics.metric

import dev.ktrics.ir.Lang

/**
 * Effective per-metric settings the engine applies. The default resolves to the catalogue
 * (a metric is enabled iff [MetricDef.enabledByDefault]; thresholds are the catalogue defaults +
 * per-language overrides). The config module supplies an implementation backed by
 * ktrics.yaml that can enable off-by-default metrics, override thresholds, or disable a metric.
 */
interface MetricSettings {
    fun isEnabled(def: MetricDef): Boolean = def.enabledByDefault

    fun thresholds(
        def: MetricDef,
        lang: Lang,
    ): Thresholds = def.thresholdsFor(lang)

    object Default : MetricSettings
}
