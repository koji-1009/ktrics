package dev.ktrics.config

/**
 * Framework presets that expand to keep-alive annotations for unused detection. A symbol
 * carrying one of these annotations is reachable even with no source reference (a framework or
 * annotation processor calls it reflectively). Unknown presets are silently ignored (forward-compat).
 */
object Presets {
    private val table: Map<String, List<String>> =
        mapOf(
            "lombok" to
                listOf(
                    "Data",
                    "Builder",
                    "Value",
                    "Getter",
                    "Setter",
                    "RequiredArgsConstructor",
                    "AllArgsConstructor",
                    "NoArgsConstructor",
                ),
            "jpa" to listOf("Entity", "Embeddable", "MappedSuperclass", "Table", "Column", "Id"),
            "jackson" to listOf("JsonProperty", "JsonCreator", "JsonValue", "JsonSerialize", "JsonDeserialize"),
            "spring" to
                listOf(
                    "Component",
                    "Service",
                    "Repository",
                    "Controller",
                    "RestController",
                    "Configuration",
                    "Bean",
                    "Autowired",
                    "EventListener",
                    "Scheduled",
                ),
            "kotlinx-serialization" to listOf("Serializable", "SerialName"),
            "room" to listOf("Entity", "Dao", "Database", "TypeConverter"),
            "compose" to listOf("Composable", "Preview"),
            "parcelize" to listOf("Parcelize"),
            "moshi" to listOf("JsonClass", "Json"),
            "dagger" to listOf("Inject", "Module", "Provides", "Binds", "Component", "Singleton"),
        )

    /** Annotation simple names that keep a symbol alive, from the named presets + explicit list. */
    fun keepAliveAnnotations(
        presets: List<String>,
        explicit: List<String>,
    ): Set<String> = (presets.flatMap { table[it].orEmpty() } + explicit).toSet()

    fun known(): Set<String> = table.keys
}
