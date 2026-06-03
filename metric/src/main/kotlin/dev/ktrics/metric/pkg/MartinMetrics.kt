package dev.ktrics.metric.pkg

import dev.ktrics.ir.TypeKind
import dev.ktrics.metric.AppliesTo
import dev.ktrics.metric.MeasureContext
import dev.ktrics.metric.MetricDef
import dev.ktrics.metric.PackageMetric
import dev.ktrics.metric.PackageUnit
import dev.ktrics.metric.Polarity
import dev.ktrics.metric.ScopeKind
import dev.ktrics.metric.Thresholds
import kotlin.math.abs

/**
 * Martin 1994 package metrics. The release-unit granularity matches Java/Kotlin packages
 * exactly — the strongest fit of the whole suite. "External" is defined by the module graph via the
 * [dev.ktrics.metric.ProjectIndex], never by guesswork.
 */

private fun abstractness(pkg: PackageUnit): Double {
    val total = pkg.types.size
    if (total == 0) return 0.0
    val abstractCount =
        pkg.types.count {
            it.isAbstract ||
                it.kind == TypeKind.INTERFACE ||
                it.kind == TypeKind.ANNOTATION ||
                it.kind == TypeKind.SEALED
        }
    return abstractCount.toDouble() / total
}

private fun instability(
    pkg: PackageUnit,
    ctx: MeasureContext,
): Double {
    val index = ctx.index ?: return 0.0
    val ce = index.efferentPackagesOf(pkg.name).size
    val ca = index.afferentPackagesOf(pkg.name).size
    val denom = ce + ca
    return if (denom == 0) 0.0 else ce.toDouble() / denom
}

/** Ce: distinct external packages imported. */
class EfferentCoupling : PackageMetric {
    override val def =
        MetricDef(
            id = "efferent-coupling",
            scopeKind = ScopeKind.PACKAGE,
            polarity = Polarity.LOWER_IS_BETTER,
            appliesTo = AppliesTo.BOTH,
            source = "Martin 1994",
            rationale =
                "Number of distinct external packages this package depends on (outgoing). High Ce makes " +
                    "the package sensitive to churn in many others.",
            refactorHints = listOf("Depend on fewer, more stable packages; introduce a boundary interface."),
            references = listOf("Martin, R. (1994). OO Design Quality Metrics."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Double = (ctx.index?.efferentPackagesOf(pkg.name)?.size ?: 0).toDouble()
}

/** Ca: packages depending on this one (incoming). Informational. */
class AfferentCoupling : PackageMetric {
    override val def =
        MetricDef(
            id = "afferent-coupling",
            scopeKind = ScopeKind.PACKAGE,
            polarity = Polarity.INFORMATIONAL,
            appliesTo = AppliesTo.BOTH,
            source = "Martin 1994",
            rationale =
                "Number of in-project packages that depend on this one (incoming). High Ca marks a package " +
                    "as widely relied upon — it should be correspondingly stable and abstract.",
            refactorHints = listOf("Keep widely-depended-on packages stable; avoid concrete details leaking out."),
            references = listOf("Martin, R. (1994). OO Design Quality Metrics."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Double = (ctx.index?.afferentPackagesOf(pkg.name)?.size ?: 0).toDouble()
}

/** I = Ce / (Ca + Ce). Informational. */
class Instability : PackageMetric {
    override val def =
        MetricDef(
            id = "instability",
            scopeKind = ScopeKind.PACKAGE,
            polarity = Polarity.INFORMATIONAL,
            appliesTo = AppliesTo.BOTH,
            source = "Martin 1994",
            rationale =
                "Ce / (Ca + Ce): 0 = maximally stable (only depended upon), 1 = maximally unstable (only " +
                    "depends on others). Read alongside abstractness via distance-from-main-sequence.",
            refactorHints = listOf("Stable packages (low I) should be abstract; unstable packages may be concrete."),
            references = listOf("Martin, R. (1994). OO Design Quality Metrics."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Double = instability(pkg, ctx)
}

/** A = (abstract + interface types) / total types. */
class Abstractness : PackageMetric {
    override val def =
        MetricDef(
            id = "abstractness",
            scopeKind = ScopeKind.PACKAGE,
            polarity = Polarity.INFORMATIONAL,
            appliesTo = AppliesTo.BOTH,
            source = "Martin 1994",
            rationale =
                "Fraction of the package's types that are abstract or interfaces. Pairs with instability: " +
                    "stable packages should be abstract, unstable ones concrete.",
            refactorHints = listOf("Raise abstractness of stable packages by extracting interfaces."),
            references = listOf("Martin, R. (1994). OO Design Quality Metrics."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Double = abstractness(pkg)
}

/**
 * D = |A + I − 1|: distance from the main sequence. INFORMATIONAL by default — it ranks change-impact
 * drift rather than firing Pain/Uselessness verdicts (dogfooding showed a hard gate is noise on every
 * leaf/utility package, where Ce=Ca=0 forces D=1). Mirrors the sibling tools' `neutral` Martin polarity;
 * opt into a threshold via ktrics.yaml. Documented in doc/calibration.md.
 */
class DistanceFromMainSequence : PackageMetric {
    override val def =
        MetricDef(
            id = "distance-from-main-sequence",
            scopeKind = ScopeKind.PACKAGE,
            polarity = Polarity.INFORMATIONAL,
            appliesTo = AppliesTo.BOTH,
            source = "Martin 1994",
            rationale =
                "|A + I − 1|: how far the package sits from the ideal balance of abstractness and stability. " +
                    "Near 0 is healthy; near 1 is the 'zone of pain' (stable+concrete) or 'zone of uselessness' (abstract+unstable). " +
                    "Informational: a change-impact ranking, not a gate — set a threshold in ktrics.yaml to enforce one.",
            refactorHints =
                listOf(
                    "Zone of pain (stable + concrete): extract interfaces to raise abstractness.",
                    "Zone of uselessness (abstract + unstable): merge or remove unused abstractions.",
                ),
            references = listOf("Martin, R. (1994). OO Design Quality Metrics."),
            defaults = Thresholds.NONE,
        )

    override fun measure(
        pkg: PackageUnit,
        ctx: MeasureContext,
    ): Double = abs(abstractness(pkg) + instability(pkg, ctx) - 1.0)
}
