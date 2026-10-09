package com.lmreader.ui.queue

import org.junit.Assert.*
import org.junit.Test

class TranslationSchedulingTest {
    private val a = TranslationResourcePlan(setOf("api:a", "server:a"), mapOf("a" to setOf("api:a", "server:a")))
    private val b = TranslationResourcePlan(setOf("api:b", "server:b"), mapOf("b" to setOf("api:b", "server:b")))
    private val local = TranslationResourcePlan(setOf("local-translation"))
    @Test fun independentApisAndMachineTranslationRunWhileSameApiWaits() {
        val pending = listOf("A" to a, "B" to b, "C" to local, "D" to a)
        assertEquals(listOf("A", "B", "C"), admitTranslationLanes(pending, emptyList(), TranslationSchedulingPriority.RESOURCES, 8))
        assertEquals(listOf("B", "C"), admitTranslationLanes(pending.drop(1), listOf(a), TranslationSchedulingPriority.RESOURCES, 8))
    }
    @Test fun sequentialPriorityCompletesTheLeadingManga() {
        assertEquals(listOf("A"), admitTranslationLanes(listOf("A" to a, "B" to b), emptyList(), TranslationSchedulingPriority.ORDER, 8))
        assertTrue(admitTranslationLanes(listOf("B" to b), listOf(a), TranslationSchedulingPriority.ORDER, 8).isEmpty())
    }
    @Test fun multipleApisReserveOnlyTheStepsCurrentlyWaiting() {
        val combined = TranslationResourcePlan(a.bottlenecks + b.bottlenecks, a.apiResources + b.apiResources)
        val pending = listOf("B" to b, "D" to a)
        assertEquals(listOf("B"), admitTranslationLanes(pending, listOf(combined.waitingForApi(setOf("a"))), TranslationSchedulingPriority.RESOURCES, 8))
        assertEquals(listOf("D"), admitTranslationLanes(pending, listOf(combined.waitingForApi(setOf("b"))), TranslationSchedulingPriority.RESOURCES, 8))
        assertTrue(admitTranslationLanes(pending, listOf(combined.waitingForApi(setOf("a", "b"))), TranslationSchedulingPriority.RESOURCES, 8).isEmpty())
    }
    @Test fun separateProfilesOnTheSameServerStillShareQuota() {
        val shared = TranslationResourcePlan(setOf("api:b", "server:a"))
        assertTrue(admitTranslationLanes(listOf("B" to shared), listOf(a), TranslationSchedulingPriority.RESOURCES, 8).isEmpty())
    }
}
