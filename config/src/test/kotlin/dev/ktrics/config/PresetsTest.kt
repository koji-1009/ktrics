package dev.ktrics.config

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Framework presets expand to keep-alive annotations; unknown presets are silently ignored. */
class PresetsTest {
    @Test
    fun `a known preset expands to its annotation set`() {
        val keep = Presets.keepAliveAnnotations(listOf("spring"), emptyList())
        keep shouldContainAll listOf("Component", "Service", "Repository", "Bean")
    }

    @Test
    fun `multiple presets union, plus the explicit list, deduplicated`() {
        val keep = Presets.keepAliveAnnotations(listOf("lombok", "jpa"), listOf("Keep", "Data"))
        keep shouldContainAll listOf("Builder", "Entity", "Keep")
        // "Data" is both a lombok annotation and an explicit entry; the set carries it once.
        keep.count { it == "Data" } shouldBe 1
    }

    @Test
    fun `an unknown preset contributes nothing and does not throw`() {
        val keep = Presets.keepAliveAnnotations(listOf("made-up", "spring"), emptyList())
        keep shouldContainAll listOf("Component")
        keep.contains("made-up") shouldBe false
    }

    @Test
    fun `no presets and no explicit list yields an empty set`() {
        Presets.keepAliveAnnotations(emptyList(), emptyList()) shouldBe emptySet()
    }

    @Test
    fun `known lists exactly the registered preset names`() {
        Presets.known() shouldContainAll listOf("lombok", "jpa", "spring", "compose", "dagger", "android", "ktor")
        Presets.known().contains("not-a-preset") shouldBe false
    }

    @Test
    fun `the android preset covers Hilt and Keep annotations plus the manifest-wired supertypes`() {
        val annotations = Presets.keepAliveAnnotations(listOf("android"), emptyList())
        annotations shouldContainAll listOf("Keep", "AndroidEntryPoint", "HiltAndroidApp", "HiltViewModel", "JavascriptInterface")
        val supertypes = Presets.keepAliveSupertypes(listOf("android"))
        supertypes shouldContainAll listOf("Activity", "Fragment", "Service", "BroadcastReceiver", "ContentProvider", "ViewModel")
    }

    @Test
    fun `the ktor preset keeps Resource routes and the spring preset covers the reflective surface`() {
        Presets.keepAliveAnnotations(listOf("ktor"), emptyList()) shouldContainAll listOf("Resource")
        val spring = Presets.keepAliveAnnotations(listOf("spring"), emptyList())
        spring shouldContainAll listOf("ConfigurationProperties", "ControllerAdvice", "ExceptionHandler", "PostConstruct")
    }

    @Test
    fun `presets without a supertype table contribute no supertypes`() {
        Presets.keepAliveSupertypes(listOf("spring", "ktor", "made-up")) shouldBe emptySet()
    }

    @Test
    fun `detect maps framework imports to their presets and ignores everything else`() {
        val detected =
            Presets.detect(
                listOf(
                    "androidx.appcompat.app.AppCompatActivity",
                    "org.springframework.stereotype.Service",
                    "io.ktor.server.routing.routing",
                    "kotlinx.serialization.Serializable",
                    "java.util.UUID",
                ),
            )
        detected shouldContainAll listOf("android", "spring", "ktor", "kotlinx-serialization")
        detected.contains("lombok") shouldBe false
    }

    @Test
    fun `detect finds nothing in framework-free imports`() {
        Presets.detect(listOf("java.io.File", "kotlin.collections.List", "com.example.app.Helper")) shouldBe emptySet()
    }
}
