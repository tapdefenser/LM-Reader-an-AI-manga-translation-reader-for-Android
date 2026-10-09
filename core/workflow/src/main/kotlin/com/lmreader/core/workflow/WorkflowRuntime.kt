package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface WorkflowValue {
    data class Text(val value: String) : WorkflowValue
    data class Number(val value: Double) : WorkflowValue
    data class Boolean(val value: kotlin.Boolean) : WorkflowValue
    data class Image(val key: String) : WorkflowValue
    data class Record(val fields: Map<String, WorkflowValue>) : WorkflowValue
    data class ListValue(val items: List<WorkflowValue>) : WorkflowValue
    data class Dictionary(val entries: Map<String, String>) : WorkflowValue
    data class Context(val messages: List<WorkflowContextMessage>) : WorkflowValue
}
data class WorkflowContextMessage(val role: String, val content: WorkflowExpression, val captured: Map<WorkflowRef, WorkflowValue> = emptyMap())
data class WorkflowResolvedMessage(val role: String, val content: String)
data class WorkflowApiCall(val profileId: String, val messages: List<WorkflowResolvedMessage>,
    val images: List<WorkflowValue.Image>, val resultType: WorkflowType, val expectedBubbleId: String?,
    val expectedItems: WorkflowValue.ListValue? = null, val wholeManga: Boolean = false, val stepName: String = "", val expectedCount: Int? = null)
enum class WorkflowPageAdmission { RUN, CACHED, SKIP }
class WorkflowPageStopped : CancellationException("队列调度已改变")
class WorkflowExecutionFailure(val rowId: String, val rowLabel: String, cause: Throwable) :
    IllegalStateException("$rowLabel：${cause.message ?: cause.javaClass.simpleName}", cause)

/** Platform operations are suspendable; live glossary is resolved at every read, never captured at chapter start. */
interface WorkflowRuntimeHost {
    val manga: WorkflowValue.Record
    val sourceLanguage: String
    val targetLanguage: String
    val style: String
    suspend fun chapters(): List<WorkflowValue.Record>
    suspend fun pages(chapter: WorkflowValue.Record): List<WorkflowValue.Record>
    suspend fun chapterForPage(page: WorkflowValue.Record): WorkflowValue.Record = chapters().first { chapter ->
        pages(chapter).any { it.fields["id"] == page.fields["id"] }
    }
    suspend fun glossary(): Map<String, String>
    suspend fun mergeGlossary(entries: Map<String, String>)
    suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue
    suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue
    suspend fun apiStream(call: WorkflowApiCall, frame: WorkflowFrame, item: suspend (WorkflowValue, Int) -> Unit): WorkflowValue.ListValue {
        val result = api(call, frame) as WorkflowValue.ListValue
        result.items.forEachIndexed { index, value -> item(value, index + 1) }
        return result
    }
    suspend fun previewPage(frame: WorkflowFrame) {}
    suspend fun segmentedPage(frame: WorkflowFrame) {}
    suspend fun beforePage(frame: WorkflowFrame) {}
    suspend fun admitPage(frame: WorkflowFrame, job: Job): WorkflowPageAdmission = WorkflowPageAdmission.RUN
    suspend fun publishPage(frame: WorkflowFrame) {}
    suspend fun closePage(frame: WorkflowFrame) {}
    suspend fun beforePreparationPage(frame: WorkflowFrame) = beforePage(frame)
    suspend fun admitPreparationPage(frame: WorkflowFrame, job: Job) = admitPage(frame, job)
    suspend fun completePreparationPage(frame: WorkflowFrame): WorkflowValue.ListValue =
        frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("records"))) as WorkflowValue.ListValue
    suspend fun closePreparationPage(frame: WorkflowFrame) = closePage(frame)
    suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) {}
    suspend fun pageFailed(frame: WorkflowFrame, failure: Throwable) { throw failure }
    fun step(node: WorkflowNode, frame: WorkflowFrame) {}
    fun continueScheduling(frame: WorkflowFrame? = null): Boolean = true
    fun parallelism(node: WorkflowNode): Int = WorkflowNode.MAX_PARALLEL
}

class WorkflowFrame internal constructor(val host: WorkflowRuntimeHost, private val parent: WorkflowFrame? = null,
    private val readOnlyParents: Boolean = false) {
    private val values = ConcurrentHashMap<String, WorkflowValue>()
    private val types = ConcurrentHashMap<String, WorkflowType>()
    /** Serializes appends into the variables this frame owns, so parallel branches cannot lose items. */
    private val writeLock = Mutex()
    private var bubbleIterator: String? = null
    internal var returned: WorkflowValue? = null
    internal fun child(readOnly: Boolean = false) = WorkflowFrame(host, this, readOnly)
    internal fun defineIterator(variable: WorkflowVariable, value: WorkflowValue) {
        define(variable.id, value, variable.type)
        if (variable.type == WorkflowType.BUBBLE) bubbleIterator = variable.id
    }
    internal suspend fun currentBubble(): WorkflowValue.Record? =
        bubbleIterator?.let { read(WorkflowRef(it)) as WorkflowValue.Record } ?: parent?.currentBubble()
    fun define(id: String, value: WorkflowValue, type: WorkflowType? = null) {
        val declared = type ?: types[id]
        if(declared != null) { require(WorkflowValueCodec.matches(value, declared)) { "变量初始值与声明类型不一致" }; types[id] = declared }
        values[id] = value
    }
    fun identity(id: String): String? = ((owner(id)?.values?.get(id) as? WorkflowValue.Record)?.fields?.get("id") as? WorkflowValue.Text)?.value
    private fun owner(id: String): WorkflowFrame? = if (values.containsKey(id)) this else parent?.owner(id)
    private fun canWriteOwner(id: String): Boolean = values.containsKey(id) || (!readOnlyParents && parent?.canWriteOwner(id) == true)
    suspend fun read(ref: WorkflowRef): WorkflowValue {
        var value = if(ref.variableId == WorkflowSystem.ALL_PAGES) WorkflowValue.ListValue(host.chapters().flatMap { host.pages(it) })
            else if (ref.variableId == WorkflowSystem.GLOSSARY) WorkflowValue.Dictionary(host.glossary())
            else owner(ref.variableId)?.values?.get(ref.variableId) ?: error("变量不可用：${ref.variableId}")
        ref.path.forEach { path -> value = when (val v = value) {
            is WorkflowValue.Record -> v.fields[path] ?: error("字段不存在：$path")
            is WorkflowValue.ListValue -> if (path == WorkflowReferencePaths.ITEM_COUNT) WorkflowValue.Number(v.items.size.toDouble())
                else v.items.getOrNull(path.toIntOrNull() ?: -1) ?: error("列表条目不存在：$path")
            else -> error("此值没有字段：$path")
        } }
        return value
    }
    fun canWrite(ref: WorkflowRef) = canWriteOwner(ref.variableId)
    fun write(ref: WorkflowRef, value: WorkflowValue) {
        check(canWriteOwner(ref.variableId)) { "异步分支不能写父层变量，请收集结果" }
        place(ref, value)
    }
    /** SEG replaces the page's authoritative regions even when its chosen output is a custom variable. */
    internal suspend fun replaceSegBubbles(bubbles: WorkflowValue.ListValue) {
        require(WorkflowValueCodec.matches(bubbles, WorkflowType.list(WorkflowType.BUBBLE))) { "SEG 输出必须是气泡列表" }
        check(canWriteOwner(WorkflowSystem.PAGE)) { "SEG 不能在异步分支中重建父层页面" }
        val target = owner(WorkflowSystem.PAGE) ?: error("SEG 必须位于每页内")
        target.writeLock.withLock {
            val page = target.values.getValue(WorkflowSystem.PAGE) as WorkflowValue.Record
            target.values[WorkflowSystem.PAGE] = page.copy(fields = page.fields + mapOf(
                "bubbles" to bubbles, "records" to WorkflowValue.ListValue(emptyList())))
        }
    }
    /**
     * Appends are commutative and run under the owning frame's lock, so nested rows may add to an outer list or
     * text even from read-only async branches without losing items. The order of parallel appends is unspecified.
     */
    suspend fun append(ref: WorkflowRef, value: WorkflowValue, merge: Boolean) {
        val target = owner(ref.variableId) ?: error("输出变量尚未声明")
        target.writeLock.withLock {
            val old = read(ref)
            val combined = when (old) {
                is WorkflowValue.Text -> WorkflowValue.Text(old.value + ((value as? WorkflowValue.Text)?.value ?: error("文本只能追加文本")))
                is WorkflowValue.ListValue -> old.copy(items = old.items + if (merge) (value as? WorkflowValue.ListValue)?.items
                    ?: error("追加条目的类型不匹配") else listOf(value))
                else -> error("只能追加到文本或列表")
            }
            place(ref, combined)
        }
    }
    private fun place(ref: WorkflowRef, value: WorkflowValue) {
        val target = owner(ref.variableId) ?: error("输出变量尚未声明")
        val expected = target.types[ref.variableId]?.let { WorkflowReferencePaths.resolve(it, ref.path) }?.also {
            require(!it.readOnly) { "列表项数只读" }
        }?.type
        expected?.let { require(WorkflowValueCodec.matches(value, it)) { "输出值与声明类型不一致：${WorkflowLabels.type(it)}" } }
        fun replace(old: WorkflowValue, path: List<String>): WorkflowValue {
            if (path.isEmpty()) return value
            val key = path.first(); val rest = path.drop(1)
            return when (old) {
                is WorkflowValue.Record -> old.copy(fields = old.fields + (key to replace(old.fields[key] ?: error("字段不存在"), rest)))
                is WorkflowValue.ListValue -> {
                    require(key != WorkflowReferencePaths.ITEM_COUNT) { "列表项数只读" }
                    val index = key.toInt(); require(index in old.items.indices) { "列表条目不存在：$key" }
                    old.copy(items = old.items.mapIndexed { i, item -> if (i == index) replace(item, rest) else item })
                }
                else -> error("输出字段无效")
            }
        }
        target.values[ref.variableId] = replace(target.values.getValue(ref.variableId), ref.path)
    }
    suspend fun evaluate(value: WorkflowExpression, captured: Map<WorkflowRef, WorkflowValue> = emptyMap()): WorkflowValue = when (value) {
        is WorkflowExpression.Text -> WorkflowValue.Text(value.value)
        is WorkflowExpression.Number -> WorkflowValue.Number(value.value)
        is WorkflowExpression.Boolean -> WorkflowValue.Boolean(value.value)
        is WorkflowExpression.Ref -> if(value.value.variableId == WorkflowSystem.GLOSSARY) read(value.value) else captured[value.value] ?: read(value.value)
        is WorkflowExpression.Empty -> empty(value.type)
        is WorkflowExpression.Record -> WorkflowValue.Record(value.fields.mapValues { evaluate(it.value, captured) })
        is WorkflowExpression.Template -> {
            val text = StringBuilder(); var start = 0
            for (match in Regex("\\$\\{([^}]+)\\}").findAll(value.text)) {
                text.append(value.text.substring(start, match.range.first))
                val ref = value.bindings[match.groupValues[1]] ?: error("未绑定提示词变量")
                text.append(WorkflowValueCodec.display(if(ref.variableId == WorkflowSystem.GLOSSARY) read(ref) else captured[ref] ?: read(ref)))
                start = match.range.last + 1
            }
            text.append(value.text.substring(start)); WorkflowValue.Text(text.toString())
        }
    }
    suspend fun capture(value: WorkflowExpression): Map<WorkflowRef, WorkflowValue> {
        fun refs(e: WorkflowExpression): List<WorkflowRef> = when(e) {
            is WorkflowExpression.Ref -> listOf(e.value); is WorkflowExpression.Template -> Regex("\\$\\{([^}]+)\\}").findAll(e.text).mapNotNull { e.bindings[it.groupValues[1]] }.toList()
            is WorkflowExpression.Record -> e.fields.values.flatMap(::refs); else -> emptyList()
        }
        return refs(value).filterNot { it.variableId == WorkflowSystem.GLOSSARY }.distinct().associateWith { read(it) }
    }
    suspend fun text(id: String, vararg path: String) = (read(WorkflowRef(id, path.toList())) as WorkflowValue.Text).value
    companion object {
        fun empty(type: WorkflowType): WorkflowValue = when (type.kind) {
            WorkflowDataKind.TEXT -> WorkflowValue.Text("")
            WorkflowDataKind.NUMBER -> WorkflowValue.Number(0.0)
            WorkflowDataKind.BOOLEAN -> WorkflowValue.Boolean(false)
            WorkflowDataKind.LIST -> WorkflowValue.ListValue(emptyList())
            WorkflowDataKind.DICTIONARY -> WorkflowValue.Dictionary(emptyMap())
            WorkflowDataKind.CONTEXT -> WorkflowValue.Context(emptyList())
            WorkflowDataKind.RECORD -> WorkflowValue.Record(type.fields.mapValues { empty(it.value) })
            else -> error("图片和气泡必须来自页面或 SEG")
        }
    }
}

/** Bounded workers pull references, not a future per image. All child branches join before the next row. */
class WorkflowRuntime(private val concurrency: Int = 8, private val retries: Int = 0) {
    init { require(concurrency in 1..8 && retries in 0..5) }
    private val pages = Semaphore(concurrency)
    private val pageIndexes = java.util.IdentityHashMap<WorkflowValue.ListValue, Map<String, WorkflowValue.ListValue>>()
    suspend fun execute(program: WorkflowProgram, host: WorkflowRuntimeHost) {
        val validation = WorkflowValidator.validate(program)
        require(validation.valid) { validation.issues.joinToString("；") { it.message } }
        val root = WorkflowFrame(host)
        executeRows(program.rows, root)
    }
    private suspend fun executeRows(rows: List<WorkflowNode>, frame: WorkflowFrame): Boolean {
        for (node in rows) {
            currentCoroutineContext().ensureActive()
            if (!frame.host.continueScheduling(frame)) return false
            frame.host.step(node, frame)
            try { if (!executeNode(node, frame)) return false; if(frame.returned != null) return true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: WorkflowExecutionFailure) { throw failure }
            catch (failure: Exception) { throw WorkflowExecutionFailure(node.id, node.label.ifBlank { WorkflowLabels.kind(node.kind) }, failure) }
        }
        return true
    }
    private suspend fun executeNode(n: WorkflowNode, frame: WorkflowFrame): Boolean {
        val host = frame.host
        suspend fun input(key: String) = frame.evaluate(n.inputs[key] ?: error("缺少参数：$key"))
        fun output(value: WorkflowValue) = frame.write(n.target ?: error("缺少输出变量"), value)
        when (n.kind) {
            WorkflowKind.MANGA -> {
                val child = frame.child()
                child.define(WorkflowSystem.MANGA, host.manga, WorkflowSystem.mangaType); child.define(WorkflowSystem.SOURCE, WorkflowValue.Text(host.sourceLanguage), WorkflowType.TEXT)
                child.define(WorkflowSystem.TARGET, WorkflowValue.Text(host.targetLanguage), WorkflowType.TEXT); child.define(WorkflowSystem.STYLE, WorkflowValue.Text(host.style), WorkflowType.TEXT)
                return executeRows(n.children, child)
            }
            WorkflowKind.CHAPTERS -> {
                val chapters = host.chapters(); val complete = BooleanArray(chapters.size)
                bounded(chapters, n, host) { index, chapter ->
                    if (!host.continueScheduling()) return@bounded
                    val child = frame.child(n.mode == WorkflowMode.ASYNC)
                    child.define(WorkflowSystem.CHAPTER, chapter, WorkflowSystem.chapterType)
                    try { complete[index] = executeRows(n.children, child) }
                    finally { host.chapterFinished(child, complete[index]) }
                }
                return complete.all { it }
            }
            WorkflowKind.PAGES -> {
                val chapter = frame.read(WorkflowRef(WorkflowSystem.CHAPTER)) as WorkflowValue.Record
                val pageList = host.pages(chapter); val complete = BooleanArray(pageList.size)
                val records = arrayOfNulls<WorkflowValue.ListValue>(pageList.size)
                bounded(pageList, n, host) { index, page ->
                    if (!host.continueScheduling()) return@bounded
                    val child = frame.child(n.mode == WorkflowMode.ASYNC); child.define(WorkflowSystem.PAGE, page, WorkflowSystem.pageType)
                    host.beforePage(child)
                    pages.withPermit {
                    if (!host.continueScheduling()) return@withPermit
                    supervisorScope {
                        val work = async {
                            try {
                                when (host.admitPage(child, currentCoroutineContext().job)) {
                                    WorkflowPageAdmission.SKIP -> return@async false
                                    WorkflowPageAdmission.RUN -> if (!executeRows(n.children, child)) return@async false
                                    WorkflowPageAdmission.CACHED -> Unit
                                }
                                host.publishPage(child)
                                records[index] = child.read(WorkflowRef(WorkflowSystem.PAGE, listOf("records"))) as WorkflowValue.ListValue
                                true
                            } catch (stop: WorkflowPageStopped) { false }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { host.pageFailed(child, failure); false }
                            finally { withContext(NonCancellable) { host.closePage(child) } }
                        }
                        try { complete[index] = work.await() }
                        catch (stop: WorkflowPageStopped) { complete[index] = false }
                    }
                } }
                frame.define(WorkflowSystem.CHAPTER, chapter.copy(fields = chapter.fields + ("records" to WorkflowValue.ListValue(records.filterNotNull().flatMap { it.items }))))
                return complete.all { it }
            }
            WorkflowKind.PREPARE_MANGA -> {
                val chapters = host.chapters(); val complete = BooleanArray(chapters.size)
                val chapterRecords = arrayOfNulls<WorkflowValue.ListValue>(chapters.size)
                bounded(chapters, n, host) { chapterIndex, chapter ->
                    val chapterFrame = frame.child(true); chapterFrame.define(WorkflowSystem.CHAPTER, chapter, WorkflowSystem.chapterType)
                    val pageList = host.pages(chapter); val finished = BooleanArray(pageList.size)
                    val records = arrayOfNulls<WorkflowValue.ListValue>(pageList.size)
                    bounded(pageList, n, host) { index, page ->
                        if(!host.continueScheduling()) return@bounded
                        val child = chapterFrame.child(true); child.define(WorkflowSystem.PAGE, page, WorkflowSystem.pageType)
                        host.beforePreparationPage(child)
                        pages.withPermit {
                            if(!host.continueScheduling()) return@withPermit
                            supervisorScope {
                                val work = async {
                                    try {
                                        when(host.admitPreparationPage(child, currentCoroutineContext().job)) {
                                            WorkflowPageAdmission.SKIP -> return@async false
                                            WorkflowPageAdmission.RUN -> if(!executeRows(n.children, child)) return@async false
                                            WorkflowPageAdmission.CACHED -> Unit
                                        }
                                        records[index] = host.completePreparationPage(child); true
                                    } catch(stop: WorkflowPageStopped) { false }
                                    catch(cancelled: CancellationException) { throw cancelled }
                                    catch(failure: Exception) { host.pageFailed(child, failure); false }
                                    finally { withContext(NonCancellable) { host.closePreparationPage(child) } }
                                }
                                try { finished[index] = work.await() } catch(stop: WorkflowPageStopped) { finished[index] = false }
                            }
                        }
                    }
                    complete[chapterIndex] = finished.all { it }
                    if(!complete[chapterIndex]) host.chapterFinished(chapterFrame, false)
                    chapterRecords[chapterIndex] = WorkflowValue.ListValue(records.filterNotNull().flatMap { it.items })
                }
                if(!complete.all { it }) return false
                output(WorkflowValue.ListValue(chapterRecords.filterNotNull().flatMap { it.items }))
            }
            WorkflowKind.EACH -> {
                val itemsValue = input("items")
                val items = when (itemsValue) {
                    is WorkflowValue.ListValue -> itemsValue.items
                    is WorkflowValue.Dictionary -> itemsValue.entries.map { WorkflowValue.Record(mapOf("key" to WorkflowValue.Text(it.key), "value" to WorkflowValue.Text(it.value))) }
                    else -> error("只能遍历列表或字典")
                }
                require(items.size <= 10_000) { "列表最多 10000 项" }
                val iterator = n.variable ?: error("缺少循环变量")
                val values = arrayOfNulls<WorkflowValue>(items.size); val results = arrayOfNulls<WorkflowValue>(items.size)
                val complete = BooleanArray(items.size)
                bounded(items, n, host) { index, item ->
                    val child = frame.child(n.mode == WorkflowMode.ASYNC); child.defineIterator(iterator, item)
                    suspend fun executeItem(): Boolean {
                        if(!executeRows(n.children, child)) return false
                        values[index] = child.read(WorkflowRef(iterator.id))
                        results[index] = n.inputs["collectValue"]?.let { child.evaluate(it) } ?: child.returned
                        return true
                    }
                    if(iterator.type == WorkflowSystem.pageType) {
                        child.define(WorkflowSystem.CHAPTER, host.chapterForPage(item as WorkflowValue.Record), WorkflowSystem.chapterType)
                        val publish = n.inputs["publish"] != WorkflowExpression.Boolean(false)
                        if(publish) host.beforePage(child) else host.beforePreparationPage(child)
                        pages.withPermit {
                            if(!host.continueScheduling()) return@withPermit
                            supervisorScope {
                                val work = async {
                                    try {
                                        val admission = if(publish) host.admitPage(child, currentCoroutineContext().job) else host.admitPreparationPage(child, currentCoroutineContext().job)
                                        if(admission == WorkflowPageAdmission.SKIP) return@async false
                                        if(admission == WorkflowPageAdmission.RUN && !executeRows(n.children, child)) return@async false
                                        if(publish) host.publishPage(child) else host.completePreparationPage(child)
                                        values[index] = child.read(WorkflowRef(iterator.id))
                                        results[index] = n.inputs["collectValue"]?.let { child.evaluate(it) } ?: child.returned
                                        true
                                    } catch(stop: WorkflowPageStopped) { false }
                                    catch(cancelled: CancellationException) { throw cancelled }
                                    catch(failure: Exception) { host.pageFailed(child, failure); host.chapterFinished(child, false); false }
                                    finally { withContext(NonCancellable) { if(publish) host.closePage(child) else host.closePreparationPage(child) } }
                                }
                                try { complete[index] = work.await() } catch(stop: WorkflowPageStopped) { complete[index] = false }
                            }
                        }
                    } else complete[index] = executeItem()
                }
                if (!complete.all { it }) return false
                val collectionRef = (n.inputs["items"] as? WorkflowExpression.Ref)?.value
                if (itemsValue is WorkflowValue.ListValue && collectionRef != null && frame.canWrite(collectionRef))
                    frame.write(collectionRef, WorkflowValue.ListValue(values.filterNotNull()))
                n.collectTo?.let { ref ->
                    require(results.all { it != null }) { "收集结果的每个分支需要返回值" }
                    frame.write(ref, WorkflowValue.ListValue(if(n.inputs["flatten"] == WorkflowExpression.Boolean(true)) results.filterNotNull().flatMap { (it as WorkflowValue.ListValue).items } else results.filterNotNull()))
                }
            }
            WorkflowKind.IF -> {
                val child = frame.child()
                val complete = executeRows(if ((input("condition") as WorkflowValue.Boolean).value) n.children else n.otherwise, child)
                frame.returned = child.returned; return complete
            }
            WorkflowKind.DECLARE -> n.variable?.let { frame.define(it.id, n.inputs["value"]?.let { v -> frame.evaluate(v) } ?: WorkflowFrame.empty(it.type), it.type) }
            WorkflowKind.SET -> output(input("value"))
            WorkflowKind.SEG -> {
                val result = retryEngine { host.request(n.kind, n.inputs.mapValues { frame.evaluate(it.value) }, frame) }
                frame.replaceSegBubbles(result as? WorkflowValue.ListValue ?: error("SEG 输出必须是气泡列表"))
                output(result)
                host.segmentedPage(frame)
            }
            WorkflowKind.OCR, WorkflowKind.TRANSLATE -> output(retryEngine { host.request(n.kind, n.inputs.mapValues { frame.evaluate(it.value) }, frame) })
            WorkflowKind.API, WorkflowKind.API_STREAM -> {
                val messages = mutableListOf<WorkflowResolvedMessage>()
                n.inputs["context"]?.let { expression ->
                    val context = frame.evaluate(expression) as WorkflowValue.Context
                    context.messages.forEach { messages += WorkflowResolvedMessage(it.role, WorkflowValueCodec.display(frame.evaluate(it.content, it.captured))) }
                }
                messages += WorkflowResolvedMessage("user", (input("prompt") as WorkflowValue.Text).value)
                val bubble = frame.currentBubble()
                val images = n.inputs["images"]?.let { frame.evaluate(it) }?.let { v -> when (v) {
                    is WorkflowValue.Image -> listOf(v)
                    is WorkflowValue.ListValue -> v.items.map { it as WorkflowValue.Image }
                    else -> error("图片附件类型错误")
                } } ?: if (n.inputs["attachCurrentImage"] != WorkflowExpression.Boolean(false))
                    listOfNotNull(bubble?.fields?.get("image") as? WorkflowValue.Image) else emptyList()
                val bubbleId = (bubble?.fields?.get("id") as? WorkflowValue.Text)?.value
                val expected = n.inputs["expectedBubbles"]?.let { frame.evaluate(it) as WorkflowValue.ListValue }
                val expectedCount = n.inputs["expectedCount"]?.let {
                    val count = (frame.evaluate(it) as WorkflowValue.Number).value
                    require(count.isFinite() && count >= 0 && count % 1 == 0.0 && count <= Int.MAX_VALUE) { "期望输出项数必须是非负整数" }
                    count.toInt()
                }
                val wholeManga = (n.inputs["wholeManga"] as? WorkflowExpression.Boolean)?.value == true
                val call = WorkflowApiCall((input("profile") as WorkflowValue.Text).value, messages, images, n.resultType, bubbleId, expected, wholeManga, n.label.ifBlank { WorkflowLabels.kind(n.kind) }, expectedCount)
                val accumulated = if(n.kind == WorkflowKind.API_STREAM) mutableListOf<WorkflowValue>() else null
                val result = if(expected != null && expected.items.isEmpty() || expectedCount == 0) WorkflowValue.ListValue(emptyList())
                    else if(n.kind == WorkflowKind.API_STREAM) {
                        host.apiStream(call, frame) { item, index ->
                            requireNotNull(accumulated).add(item); output(WorkflowValue.ListValue(accumulated.toList()))
                            val child = frame.child()
                            val variable = requireNotNull(n.variable)
                            child.define(variable.id, item, variable.type)
                            child.define(WorkflowSystem.STREAM_INDEX, WorkflowValue.Number(index.toDouble()), WorkflowType.NUMBER)
                            if(!executeRows(n.children, child)) throw WorkflowPageStopped()
                            accumulated[index - 1] = child.read(WorkflowRef(variable.id))
                            output(WorkflowValue.ListValue(accumulated.toList()))
                        }
                    } else host.api(call, frame)
                expected?.let { WorkflowValueCodec.requireMatchingTranslations(result, it) }
                expectedCount?.let { WorkflowValueCodec.requireItemCount(result, it) }
                output(accumulated?.let { WorkflowValue.ListValue(it.toList()) } ?: result)
            }
            WorkflowKind.APPLY_TRANSLATIONS -> {
                val provided = input("items")
                val single = provided is WorkflowValue.Record
                val result = if(single) WorkflowValue.ListValue(listOf(provided)) else provided as WorkflowValue.ListValue
                val pageId = frame.identity(WorkflowSystem.PAGE) ?: error("回填需要本页")
                val local = if(result.items.firstOrNull()?.let { (it as WorkflowValue.Record).fields.containsKey("pageId") } == true) {
                    synchronized(pageIndexes) {
                        pageIndexes.getOrPut(result) {
                            result.items.groupBy { ((it as WorkflowValue.Record).fields.getValue("pageId") as WorkflowValue.Text).value }
                                .mapValues { WorkflowValue.ListValue(it.value) }
                        }[pageId] ?: WorkflowValue.ListValue(emptyList())
                    }
                } else result
                val ref = WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))
                val bubbles = frame.read(ref) as WorkflowValue.ListValue
                if(single) {
                    require(local.items.size == 1) { "该气泡不属于本页" }
                    val id = (local.items.single() as WorkflowValue.Record).fields["bubbleId"]
                    val original = bubbles.items.singleOrNull { (it as WorkflowValue.Record).fields["id"] == id } ?: error("气泡 ID 不属于本页")
                    WorkflowValueCodec.requireMatchingTranslations(local, WorkflowValue.ListValue(listOf(original)))
                } else WorkflowValueCodec.requireMatchingTranslations(local, bubbles)
                val byId = local.items.associate { row -> (row as WorkflowValue.Record).fields.getValue("bubbleId") to row }
                frame.write(ref, WorkflowValue.ListValue(bubbles.items.map { row ->
                    val bubble = row as WorkflowValue.Record; val translation = byId[bubble.fields.getValue("id")]
                    if(translation == null) bubble else bubble.copy(fields = bubble.fields + mapOf("source" to translation.fields.getValue("source"), "translation" to translation.fields.getValue("translation")))
                }))
                host.previewPage(frame)
            }
            WorkflowKind.APPLY_ORDER -> {
                val provided = input("items")
                val rows = if(provided is WorkflowValue.Record) listOf(provided) else (provided as WorkflowValue.ListValue).items.map { it as WorkflowValue.Record }
                val pageId = frame.identity(WorkflowSystem.PAGE) ?: error("回填需要本页")
                val records = rows.filter { it.fields["pageId"]?.let { id -> (id as WorkflowValue.Text).value == pageId } ?: true }
                if(provided is WorkflowValue.Record) require(records.size == 1) { "该条目不属于本页" }
                val number = (input("index") as WorkflowValue.Number).value
                require(number.isFinite() && number % 1 == 0.0 && number >= 1) { "起始气泡序号必须是从 1 开始的整数" }
                val start = number.toInt() - 1
                val ref = WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))
                val bubbles = frame.read(ref) as WorkflowValue.ListValue
                require(start.toLong() + records.size <= bubbles.items.size) { "译文条目超出本页气泡数" }
                frame.write(ref, WorkflowValue.ListValue(bubbles.items.mapIndexed { index, value ->
                    val translated = records.getOrNull(index - start)
                    if(translated == null) value else {
                        val bubble = value as WorkflowValue.Record
                        translated.fields["source"]?.let { source ->
                            val original = bubble.fields["source"] as WorkflowValue.Text
                            require(original.value.isEmpty() || original == source) { "不能修改 OCR 原文" }
                        }
                        bubble.copy(fields = bubble.fields + mapOf("translation" to translated.fields.getValue("translation")) + translated.fields["source"]?.let { mapOf("source" to it) }.orEmpty())
                    }
                }))
                host.previewPage(frame)
            }
            WorkflowKind.APPEND, WorkflowKind.MERGE_LIST -> frame.append(n.target ?: error("缺少输出变量"), input("value"), n.kind == WorkflowKind.MERGE_LIST)
            WorkflowKind.REPLACE -> {
                val text = (input("text") as WorkflowValue.Text).value; val dict = (input("dictionary") as WorkflowValue.Dictionary).entries
                val keys = dict.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
                output(WorkflowValue.Text(if (keys.isEmpty()) text else Regex(keys.joinToString("|") { Regex.escape(it) }).replace(text) { dict.getValue(it.value) }))
            }
            WorkflowKind.MESSAGE -> {
                val context = frame.read(n.target!!) as WorkflowValue.Context
                val user = n.inputs.getValue("user")
                val messages = listOf(WorkflowContextMessage("user", user, frame.capture(user))) + n.inputs["assistant"]?.let { listOf(WorkflowContextMessage("assistant", it, frame.capture(it))) }.orEmpty()
                output(context.copy(messages = context.messages + messages))
            }
            WorkflowKind.MERGE_GLOSSARY -> {
                val entries = (input("items") as WorkflowValue.ListValue).items.map { it as WorkflowValue.Record }
                val first = linkedMapOf<String, String>()
                entries.forEach { entry ->
                    val source = (entry.fields.getValue("source") as WorkflowValue.Text).value.trim()
                    val target = (entry.fields.getValue("translation") as WorkflowValue.Text).value.trim()
                    if (source.isNotBlank() && target.isNotBlank()) first.putIfAbsent(source, target)
                }
                host.mergeGlossary(first)
            }
            WorkflowKind.RETURN -> frame.returned = input("value")
        }
        return true
    }
    private suspend fun <T> retryEngine(action: suspend () -> T): T {
        repeat(retries) { attempt ->
            try { return action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { delay(250L * (attempt + 1)) }
        }
        return action()
    }
    /** Bound pipeline workers independently; each actual engine request shares its resource pool. */
    private fun parallel(node: WorkflowNode, host: WorkflowRuntimeHost) = minOf(concurrency, node.parallelLimit ?: host.parallelism(node).coerceIn(1, WorkflowNode.MAX_PARALLEL))
    private suspend fun <T> bounded(items: List<T>, node: WorkflowNode, host: WorkflowRuntimeHost, action: suspend (Int, T) -> Unit) = coroutineScope {
        val next = AtomicInteger()
        List(minOf(if (node.mode == WorkflowMode.SYNC) 1 else parallel(node, host), items.size)) {
            launch { while (true) { ensureActive(); val i = next.getAndIncrement(); if (i >= items.size) break; action(i, items[i]) } }
        }.joinAll()
    }
}
