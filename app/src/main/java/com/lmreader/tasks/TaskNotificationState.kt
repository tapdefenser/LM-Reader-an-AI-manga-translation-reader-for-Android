package com.lmreader.tasks

enum class TaskQueue(val key: String) { TRANSLATION("translation"), EXPORT("export") }
enum class TaskNoticePhase { WAITING, RUNNING, PAUSING, PAUSED, FAILED, COMPLETED }

data class TaskNoticeItem(val id: String, val state: String, val manga: String = "", val chapter: String = "",
    val completed: Int = 0, val total: Int = 0, val error: String? = null)
data class TaskNotice(val queue: TaskQueue, val phase: TaskNoticePhase, val item: TaskNoticeItem,
    val remaining: Int, val failures: Int = 0, val completedChapters: Int = 0,
    val seg: Int = 0, val ocr: Int = 0, val api: Int = 0, val singlePage: Boolean = false) {
    val working get() = phase in setOf(TaskNoticePhase.WAITING, TaskNoticePhase.RUNNING, TaskNoticePhase.PAUSING)
}

/** Observe real terminal records: disappearance or cancellation alone is never success. */
class TaskNoticeTracker(private val queue: TaskQueue) {
    private var previous = emptyMap<String, TaskNoticeItem>()
    private var completion: TaskNotice? = null
    private val done = mutableSetOf<String>()
    private var pagesDone = 0
    fun update(items: List<TaskNoticeItem>, paused: Boolean, priorityPage: String? = null): TaskNotice? {
        val live = items.filter { it.state !in setOf("DONE", "CANCELLED") }
        if(previous.values.none { it.state in setOf("PENDING", "RUNNING", "PAUSED") } &&
            live.any { it.state in setOf("PENDING", "RUNNING") }) {
            completion = null; done.clear(); pagesDone = 0
        }
        val completed = items.filter { it.state == "DONE" && previous[it.id]?.state?.let { state -> state != "DONE" } == true && done.add(it.id) }
        pagesDone += completed.sumOf { it.completed.coerceAtLeast(0) }
        if(completed.isNotEmpty()) completion = TaskNotice(queue, TaskNoticePhase.COMPLETED,
            completed.last().copy(completed = pagesDone, total = pagesDone), 0, completedChapters = done.size)
        if(live.isEmpty() && previous.values.any { it.state !in setOf("DONE", "CANCELLED") } && completed.isEmpty()) completion = null
        previous = items.associateBy { it.id }
        if(priorityPage != null) {
            completion = null
            return TaskNotice(queue, TaskNoticePhase.RUNNING,
                TaskNoticeItem("single-page", "RUNNING", chapter = priorityPage), live.size, singlePage = true)
        }
        val running = live.firstOrNull { it.state == "RUNNING" }
        val pending = live.firstOrNull { it.state == "PENDING" }
        val held = live.firstOrNull { it.state == "PAUSED" }
        val failed = live.filter { it.state in setOf("FAILED", "INTERRUPTED") }
        val phase = when {
            paused && running != null -> TaskNoticePhase.PAUSING
            paused && (pending != null || held != null) -> TaskNoticePhase.PAUSED
            running != null -> TaskNoticePhase.RUNNING
            pending != null -> TaskNoticePhase.WAITING
            held != null -> TaskNoticePhase.PAUSED
            failed.isNotEmpty() -> TaskNoticePhase.FAILED
            else -> return completion
        }
        return TaskNotice(queue, phase, requireNotNull(running ?: pending ?: held ?: failed.firstOrNull()),
            live.size, failed.size, done.size)
    }
}
