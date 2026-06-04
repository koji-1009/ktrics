package dev.ktrics.config

import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.Polarity

/**
 * Validates `ktrics.yaml` against the catalogue and schema rules. Surfaces unknown
 * metric ids, nonsensical thresholds, unknown presets (a warning — presets are forward-compat), and
 * an invalid module graph. Returns diagnostics; the `doctor` command maps them to an exit code.
 */
object Doctor {
    enum class Severity { ERROR, WARNING, INFO }

    data class Diagnostic(val severity: Severity, val message: String)

    fun check(load: ConfigLoad): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        if (load.source == null) {
            out.add(Diagnostic(Severity.INFO, "no ktrics.yaml found; using built-in defaults"))
        }
        load.problems.forEach { out.add(Diagnostic(Severity.ERROR, it)) }
        load.warnings.forEach { out.add(Diagnostic(Severity.WARNING, it)) }

        val config = load.config
        // Unknown metric ids.
        config.metrics.keys.forEach { id ->
            if (BuiltinMetrics.def(id) == null) {
                out.add(Diagnostic(Severity.ERROR, "unknown metric '$id' (run `ktrics rules` for valid ids)"))
            }
        }
        // Threshold sanity vs polarity (both-language defaults + per-language overrides).
        config.metrics.forEach { (id, entry) ->
            val def = BuiltinMetrics.def(id) ?: return@forEach
            addThresholdProblem(out, def.polarity, id, null, entry.warning to entry.error)
            entry.java?.let { addThresholdProblem(out, def.polarity, id, "java", it.warning to it.error) }
            entry.kotlin?.let { addThresholdProblem(out, def.polarity, id, "kotlin", it.warning to it.error) }
        }
        // Unknown presets — forward-compat: ignored at runtime, but worth flagging.
        config.unused.presets.forEach { p ->
            if (p !in Presets.known()) out.add(Diagnostic(Severity.WARNING, "unknown unused preset '$p' (ignored)"))
        }
        // Module graph validity (cycles, unknown deps).
        runCatching { config.modules.toGraph() }.onFailure {
            out.add(Diagnostic(Severity.ERROR, "module graph invalid: ${it.message}"))
        }
        if (out.none { it.severity == Severity.ERROR }) {
            out.add(Diagnostic(Severity.INFO, "configuration is valid"))
        }
        return out
    }

    /** Appends a threshold-vs-polarity diagnostic for one (metric, language-scope), if there is one. */
    private fun addThresholdProblem(
        out: MutableList<Diagnostic>,
        polarity: Polarity,
        id: String,
        lang: String?,
        bounds: Pair<Double?, Double?>,
    ) {
        val problem = thresholdProblem(polarity, bounds.first, bounds.second) ?: return
        val suffix = lang?.let { " ($it)" } ?: ""
        out.add(Diagnostic(Severity.WARNING, "metric '$id'$suffix: $problem"))
    }

    private fun thresholdProblem(
        polarity: Polarity,
        warning: Double?,
        error: Double?,
    ): String? {
        // Informational metrics ignore thresholds entirely: advise as soon as the user set EITHER bound,
        // not only when both are present.
        if (polarity == Polarity.INFORMATIONAL) {
            return if (warning != null || error != null) "informational metrics ignore thresholds" else null
        }
        // The ordering check compares both bounds, so it can only run when both are set.
        if (warning == null || error == null) return null
        val (misordered, relation) =
            when (polarity) {
                Polarity.LOWER_IS_BETTER -> (warning > error) to "<="
                else -> (warning < error) to ">=" // HIGHER_IS_BETTER; INFORMATIONAL returned above
            }
        return if (misordered) "warning ($warning) should be $relation error ($error)" else null
    }
}
