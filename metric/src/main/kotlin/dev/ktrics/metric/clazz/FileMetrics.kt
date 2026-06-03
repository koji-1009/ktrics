package dev.ktrics.metric.clazz

import dev.ktrics.ir.SourceUnit
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.FileMetric
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds

/**
 * File-level lenses for the Kotlin top-level declarations that escape class aggregation.
 * On Java this gap cannot occur (no top-level members), so these are scoped `kotlin`; auto-explain
 * states the structural reason. Do NOT fold top-level declarations into a synthetic class.
 */

/** Catches files used as a grab-bag of loose top-level functions/properties/types. */
class TopLevelDeclarationsPerFile : FileMetric {
    override val def =
        MetricDef(
            id = "top-level-declarations-per-file",
            scopeKind = ScopeKind.FILE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.KOTLIN,
            source = "—",
            rationale =
                "Number of top-level declarations (types + loose functions + properties) in one Kotlin " +
                    "file. A high count signals a 'utils dumping ground' that should be split by topic.",
            refactorHints =
                listOf(
                    "Group related top-level functions into their own file by feature.",
                    "Move a cluster of helpers into an object or an extension-receiver file.",
                ),
            references = emptyList(),
            defaults = Thresholds.warn(10.0),
            structuralReason = "Java files have no top-level members, so this lens is redundant there.",
        )

    override fun measure(
        unit: SourceUnit,
        ctx: MeasureContext,
    ): Double = (unit.types.size + unit.topLevelFns.size + unit.topLevelProps.size).toDouble()
}

/**
 * Number of top-level types in a file. Informational: Java is structurally capped at one public type,
 * so it only contextualises Kotlin files that declare several types together.
 */
class TypesPerFile : FileMetric {
    override val def =
        MetricDef(
            id = "types-per-file",
            scopeKind = ScopeKind.FILE,
            polarity = Polarity.INFORMATIONAL,
            appliesTo = AppliesTo.KOTLIN,
            source = "—",
            rationale =
                "Count of top-level types in the file. Informational — multiple small related types in one " +
                    "Kotlin file is often idiomatic (e.g. a sealed hierarchy), so this contextualises rather than gates.",
            refactorHints = listOf("If the types are unrelated, split them into separate files."),
            references = emptyList(),
            defaults = Thresholds.NONE,
            structuralReason = "Java caps a file at one public type, so this is informational-only there if ever enabled.",
        )

    override fun measure(
        unit: SourceUnit,
        ctx: MeasureContext,
    ): Double = unit.types.size.toDouble()
}
