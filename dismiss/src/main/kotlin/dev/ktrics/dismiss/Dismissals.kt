package dev.ktrics.dismiss

/**
 * A single dismissal directive from either the comment channel or the YAML sidecar.
 * Matches a violation by stable [id], or by ([metric] + [scope]/[file]). A blank [metric] matches any
 * metric on the targeted declaration.
 */
data class Dismissal(
    val reason: String,
    val source: String,
    val id: String? = null,
    val metric: String? = null,
    val file: String? = null,
    val scope: String? = null,
)

/** Default minimum reason length below which a dismissal is rejected and the violation stays live. */
const val DEFAULT_MIN_REASON_LENGTH: Int = 12

/** The directive text both languages use, directly above the declaration. */
object DismissSyntax {
    const val DIRECTIVE: String = "ktrics:dismiss"

    // // ktrics:dismiss <metric> reason="..."   — metric optional (omit to dismiss all on the decl).
    private val PATTERN = Regex("""ktrics:dismiss(?:\s+(?<metric>[\w-]+))?\s+reason\s*=\s*"(?<reason>[^"]*)"""")

    fun parseLine(line: String): Pair<String?, String>? {
        val match = PATTERN.find(line) ?: return null
        val metric = match.groups["metric"]?.value?.takeIf { it.isNotBlank() && it != "reason" }
        return metric to (match.groups["reason"]?.value ?: "")
    }

    fun isDirectiveLine(line: String): Boolean = line.contains(DIRECTIVE)
}
