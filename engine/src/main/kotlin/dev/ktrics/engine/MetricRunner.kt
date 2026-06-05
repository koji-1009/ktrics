package dev.ktrics.engine

import dev.ktrics.ir.FunctionDecl
import dev.ktrics.ir.Lang
import dev.ktrics.ir.SourceUnit
import dev.ktrics.ir.TypeDecl
import dev.ktrics.langapi.NodeClassifier
import dev.ktrics.metric.BuiltinMetrics
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.MetricResult
import dev.ktrics.metric.MetricSettings
import dev.ktrics.metric.PackageUnit
import dev.ktrics.metric.ProjectIndex
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.SkipPolicy
import dev.ktrics.metric.Violation

/** A full measurement pass: every measurement, plus the subset that breached a threshold. */
data class RunOutput(
    val results: List<MetricResult>,
    val violations: List<Violation>,
)

/**
 * Runs the catalogue over the lowered IR. The `appliesTo` gate is enforced here: a metric
 * scoped to one language simply does not fire on the other. Every measurement becomes a
 * [MetricResult] (kept for regression); those that breach a threshold also become a
 * [Violation] with auto-explain inline.
 */
class MetricRunner(
    private val index: ProjectIndex,
    private val settings: MetricSettings = MetricSettings.Default,
    private val skips: SkipPolicy = SkipPolicy.None,
    private val classifierFor: (Lang) -> NodeClassifier,
) {
    /** Accumulates a run's measurements + violations, so per-scope helpers take it instead of two lists. */
    private class Out {
        val results = ArrayList<MetricResult>()
        val violations = ArrayList<Violation>()
    }

    fun run(units: List<SourceUnit>): RunOutput {
        val out = Out()
        for (unit in units) {
            val ctx = MeasureContext(classifierFor(unit.lang), unit, index, isTestFile = skips.testDslDiscount(unit))
            runFunctionMetrics(ctx, out)
            runTypeMetrics(ctx, out)
            runFileMetrics(ctx, out)
        }
        runPackageMetrics(units, out)
        return RunOutput(out.results, out.violations)
    }

    private fun runFunctionMetrics(
        ctx: MeasureContext,
        out: Out,
    ) {
        val unit = ctx.unit
        // Top-level functions: scope = package.fnName (they belong to no class).
        unit.topLevelFns.forEach { fn ->
            measureFunction(fn, null, "${unit.packageName}.${fn.name}", ctx, out)
        }
        unit.allTypes().forEach { type ->
            val container = type.qualifiedName ?: "${unit.packageName}.${type.name}"
            type.methods.forEach { method -> measureFunction(method, type, "$container.${method.name}", ctx, out) }
        }
    }

    private fun measureFunction(
        fn: FunctionDecl,
        owner: TypeDecl?,
        scope: String,
        ctx: MeasureContext,
        out: Out,
    ) {
        for (metric in BuiltinMetrics.function) {
            val def = metric.def
            if (!gate(def, ctx.unit.lang)) continue
            if (skips.skipFunction(def, fn, owner, ctx.unit)) continue
            val value = metric.measure(fn, ctx) ?: continue
            record(
                def,
                MetricResult(def.id, ctx.unit.path, scope, signature(fn), ScopeKind.FUNCTION, ctx.unit.lang, value, null, fn.span),
                out,
            )
        }
    }

    private fun runTypeMetrics(
        ctx: MeasureContext,
        out: Out,
    ) {
        ctx.unit.allTypes().forEach { type ->
            val scope = type.qualifiedName ?: "${ctx.unit.packageName}.${type.name}"
            for (metric in BuiltinMetrics.type) {
                val def = metric.def
                if (!gate(def, ctx.unit.lang)) continue
                if (skips.skipType(def, type, ctx.unit)) continue
                val value = metric.measure(type, ctx) ?: continue
                record(
                    def,
                    MetricResult(
                        def.id, ctx.unit.path, scope, type.name, ScopeKind.TYPE, ctx.unit.lang, value,
                        metric.resolution(
                            type,
                            ctx,
                        ),
                        type.span,
                    ),
                    out,
                )
            }
        }
    }

    private fun runFileMetrics(
        ctx: MeasureContext,
        out: Out,
    ) {
        val unit = ctx.unit
        for (metric in BuiltinMetrics.file) {
            val def = metric.def
            if (!gate(def, unit.lang)) continue
            if (skips.skipFile(def, unit)) continue
            val value = metric.measure(unit, ctx) ?: continue
            record(def, MetricResult(def.id, unit.path, unit.path, unit.path, ScopeKind.FILE, unit.lang, value, null, unit.span), out)
        }
    }

    private fun runPackageMetrics(
        units: List<SourceUnit>,
        out: Out,
    ) {
        val byPackage = units.groupBy { it.packageName }
        for ((pkgName, pkgUnits) in byPackage) {
            // Deterministic representative: sort by path so lang/span/path are stable regardless of the
            // session's file iteration order (C5). Package metrics operate on the aggregated types/imports,
            // not one file's PSI, so any single unit serves for span/classifier — we just pin which one.
            val sorted = pkgUnits.sortedBy { it.path }
            val representative = sorted.first()
            // Lang has no "mixed" value (only JAVA/KOTLIN), and packages are language-neutral for
            // thresholds (Martin defaults apply to BOTH). For a mixed package we still pick deterministically:
            // the majority language, ties broken by enum name — so identical inputs always yield the same lang.
            val pkgLang =
                pkgUnits
                    .groupingBy { it.lang }
                    .eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<Lang, Int>> { it.value }.thenBy { it.key.name })
                    .first()
                    .key
            val pkg =
                PackageUnit(
                    name = pkgName,
                    types = pkgUnits.flatMap { it.types },
                    importedQualifiedNames = pkgUnits.flatMap { it.imports },
                    span = representative.span,
                    lang = pkgLang,
                )
            val ctx = MeasureContext(classifierFor(pkgLang), representative, index)
            for (metric in BuiltinMetrics.pkg) {
                val def = metric.def
                if (!gate(def, pkgLang)) continue
                val value = metric.measure(pkg, ctx) ?: continue
                record(
                    def,
                    MetricResult(
                        def.id, representative.path, pkgName, pkgName, ScopeKind.PACKAGE, pkgLang, value,
                        metric.resolution(
                            pkg,
                            ctx,
                        ),
                        pkg.span,
                    ),
                    out,
                )
            }
        }
    }

    /** A metric runs only when enabled AND structurally valid for the language (appliesTo gate). */
    private fun gate(
        def: MetricDef,
        lang: Lang,
    ): Boolean = settings.isEnabled(def) && def.appliesTo.matches(lang)

    /** Records a measurement and, if it breaches a threshold, the resulting violation. */
    private fun record(
        def: MetricDef,
        result: MetricResult,
        out: Out,
    ) {
        out.results.add(result)
        val thresholds = settings.thresholds(def, result.lang)
        val severity = thresholds.severityFor(result.value, def.polarity) ?: return
        val breached =
            when (severity) {
                dev.ktrics.metric.Severity.ERROR -> thresholds.error!!
                dev.ktrics.metric.Severity.WARNING -> thresholds.warning!!
            }
        out.violations.add(Violation.from(result, def, severity, breached))
    }

    private fun signature(fn: FunctionDecl): String = "${fn.name}(${fn.params.joinToString(", ") { it.typeName }})"
}
