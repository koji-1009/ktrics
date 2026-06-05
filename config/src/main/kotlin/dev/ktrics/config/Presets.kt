package dev.ktrics.config

/**
 * Framework presets for unused detection. Each expands to keep-alive ANNOTATIONS (a symbol carrying
 * one is reachable even with no source reference — a framework or annotation processor calls it
 * reflectively) and, where the framework instantiates classes by inheritance rather than annotation
 * (Android's manifest-wired components), keep-alive SUPERTYPE suffixes. Unknown presets are silently
 * ignored (forward-compat).
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
                    "ControllerAdvice",
                    "RestControllerAdvice",
                    "ExceptionHandler",
                    "Configuration",
                    "ConfigurationProperties",
                    "Bean",
                    "Autowired",
                    "EventListener",
                    "Scheduled",
                    "PostConstruct",
                    "PreDestroy",
                ),
            "kotlinx-serialization" to listOf("Serializable", "SerialName"),
            "room" to listOf("Entity", "Dao", "Database", "TypeConverter"),
            "compose" to listOf("Composable", "Preview"),
            "parcelize" to listOf("Parcelize"),
            "moshi" to listOf("JsonClass", "Json"),
            "dagger" to listOf("Inject", "Module", "Provides", "Binds", "Component", "Singleton"),
            // Android components are wired through the MANIFEST and class inheritance, not
            // annotations — the real keep-alive surface is the supertype table below. The annotations
            // here cover the explicit marker (@Keep), Hilt's reflective wiring, and the JS bridge.
            "android" to listOf("Keep", "AndroidEntryPoint", "HiltAndroidApp", "HiltViewModel", "HiltWorker", "JavascriptInterface"),
            // Ktor type-safe routing (@Resource classes are materialized by serialization). NOTE:
            // EngineMain-style modules declared in application.conf (`fun Application.module()`) are
            // reached by reflection from config and are NOT statically expressible — reference the
            // module from a `main`, or dismiss it.
            "ktor" to listOf("Resource"),
        )

    /**
     * Supertype-name SUFFIXES per preset: a type whose declared supertype ends with one of these
     * (case-sensitive, so CamelCase gives natural boundaries — `Activity` covers AppCompatActivity
     * and a project's own BaseActivity, while `View` does NOT match `Preview`) is framework-
     * instantiated and kept alive. Over-keeping is the safe direction for a deletion tool.
     */
    private val supertypeTable: Map<String, List<String>> =
        mapOf(
            "android" to
                listOf(
                    "Activity",
                    "Fragment",
                    "Service",
                    "BroadcastReceiver",
                    "ContentProvider",
                    "Application",
                    "ViewModel",
                    "View",
                    "Worker",
                ),
        )

    /** Annotation simple names that keep a symbol alive, from the named presets + explicit list. */
    fun keepAliveAnnotations(
        presets: List<String>,
        explicit: List<String>,
    ): Set<String> = (presets.flatMap { table[it].orEmpty() } + explicit).toSet()

    /** Supertype-name suffixes that keep a type alive, from the named presets. */
    fun keepAliveSupertypes(presets: List<String>): Set<String> = presets.flatMap { supertypeTable[it].orEmpty() }.toSet()

    fun known(): Set<String> = table.keys + supertypeTable.keys
}
