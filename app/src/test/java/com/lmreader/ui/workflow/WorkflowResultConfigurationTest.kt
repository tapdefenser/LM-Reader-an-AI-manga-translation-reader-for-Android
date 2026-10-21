package com.lmreader.ui.workflow

import com.lmreader.core.model.*
import org.junit.Test
import kotlin.test.*

class WorkflowResultConfigurationTest {
    private val profile = ApiProfile("api", ApiProfileKind.LLM, "display", "http://localhost/v1", model = "model-a")
    private val program = WorkflowTemplates.blank().copy(rows = listOf(WorkflowNode("api", WorkflowKind.API,
        inputs = mapOf("profile" to WorkflowExpression.Text(profile.id), "prompt" to WorkflowExpression.Text("translate")))))
    private val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
        "style", BubbleRenderSettings(), .35f, listOf(profile))
    private fun identity(program: WorkflowProgram = this.program, settings: WorkflowRunSettings = this.settings,
        models: Map<String, String> = emptyMap(), vision: VisionExecutionSettings = VisionExecutionSettings()) =
        workflowResultIdentity(program, settings, emptyList(), models, vision)

    @Test fun renderCredentialsAndSchedulingDoNotInvalidateResults() {
        val changed = settings.copy(render = BubbleRenderSettings(fontScalePercent = 150), apiProfiles = listOf(profile.copy(
            name = "renamed", apiKey = "dummy-test-only", timeoutSeconds = 42, retryCount = 0, parallelLimit = 1)))
        assertEquals(identity(), identity(settings = changed))
    }

    @Test fun modelRequestParametersAndLanguagesAreSemantic() {
        assertNotEquals(identity(), identity(settings = settings.copy(apiProfiles = listOf(profile.copy(model = "model-b")))))
        assertNotEquals(identity(), identity(settings = settings.copy(apiProfiles = listOf(profile.copy(parameters = AiParameters(temperature = .4))))))
        assertNotEquals(identity(), identity(settings = settings.copy(target = LocalTranslationLanguage.JAPANESE)))
        assertNotEquals(identity(), identity(settings = settings.copy(source = LocalTranslationLanguage.JAPANESE)))
        assertNotEquals(identity(), identity(settings = settings.copy(style = "changed")))
    }

    @Test fun customParameterOrderingIsCanonicalButValuesMatter() {
        val first = settings.copy(apiProfiles = listOf(profile.copy(customParameters = """{"a":1,"b":{"x":2,"y":3}}""")))
        val reordered = settings.copy(apiProfiles = listOf(profile.copy(customParameters = """{"b":{"y":3,"x":2},"a":1}""")))
        val changed = settings.copy(apiProfiles = listOf(profile.copy(customParameters = """{"a":2,"b":{"x":2,"y":3}}""")))
        assertEquals(identity(settings = first), identity(settings = reordered))
        assertNotEquals(identity(settings = first), identity(settings = changed))
    }

    @Test fun dynamicProfileBindingsCoverAllCapturedCandidatesWhileLiteralBindingsIgnoreUnusedProfiles() {
        val other = profile.copy(id = "other", model = "other-model")
        val original = settings.copy(apiProfiles = listOf(profile, other))
        val changed = settings.copy(apiProfiles = listOf(profile, other.copy(model = "changed")))
        assertEquals(identity(settings = original), identity(settings = changed))
        val dynamic = com.lmreader.core.workflow.WorkflowEditing.update(program, "api") {
            it.copy(inputs = it.inputs + ("profile" to WorkflowExpression.Ref(WorkflowRef("selected-profile"))))
        }
        assertNotEquals(identity(dynamic, original), identity(dynamic, changed))
    }

    @Test fun recognitionModelsThresholdsAndBackendInvalidateResultsButConcurrencyDoesNot() {
        val p = WorkflowTemplates.blank()
        val base = identity(p, models = mapOf("seg" to "v1"))
        assertNotEquals(base, identity(p, models = mapOf("seg" to "v2")))
        assertNotEquals(base, identity(p, settings.copy(segThreshold = .5f), mapOf("seg" to "v1")))
        assertNotEquals(base, identity(p, models = mapOf("seg" to "v1"), vision = VisionExecutionSettings(ocrBackend = OcrBackend.NNAPI)))
        assertEquals(base, identity(p, models = mapOf("seg" to "v1"), vision = VisionExecutionSettings(segConcurrency = 1, ocrConcurrency = 2)))
        assertEquals(base, identity(p, settings.copy(render = BubbleRenderSettings(fontScalePercent = 140)), mapOf("seg" to "v1")))
    }
}
