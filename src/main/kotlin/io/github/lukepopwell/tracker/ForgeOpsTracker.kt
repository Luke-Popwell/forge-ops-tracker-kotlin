package io.github.lukepopwell.tracker

import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point. Mirrors `sdks/java`'s own `ForgeOpsTracker` class: expressed as a Kotlin
 * `object` (a genuine language-level singleton) rather than a final class with only static
 * members and a private constructor, the same simplification a from-scratch Kotlin port gets to
 * make over the Java original.
 *
 * See that class's own doc comment for the full "installing an uncaught-exception handler doesn't
 * change whether/how the JVM exits" reasoning: verified there directly against a real uncaught
 * throw, and still true here since a Kotlin exception is a JVM `Throwable` under the hood, running
 * on the exact same JVM uncaught-exception machinery.
 */
object ForgeOpsTracker {
    private val lock = Any()
    private var performanceFlusher: PerformanceFlusher? = null

    @Volatile
    private var _configuration: Configuration? = null

    @Volatile
    private var reporter: Reporter? = null

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installedHandler = false

    // The affected user set via [setUser], if any: a ThreadLocal, the direct Kotlin/JVM analog
    // to Thread.current in the Ruby client's own equivalent, and the correct isolation boundary
    // for a typical servlet container's one-thread-per-request model.
    private val currentUser = ThreadLocal<Map<String, Any?>?>()

    // The breadcrumb trail accumulated via [addBreadcrumb], one per thread: the same isolation
    // boundary currentUser above already uses, and for the identical reason. A fresh
    // BreadcrumbBuffer is created lazily on first use per thread, not eagerly for every thread
    // that ever calls anything on this object, the same lazy-per-thread shape ThreadLocal.withInitial
    // gives for free.
    private val currentBreadcrumbs = ThreadLocal.withInitial { BreadcrumbBuffer(configuration()) }

    // The current thread's open trace, if any. Like the breadcrumb trail this is per thread, so it
    // follows blocking code; it does not follow a coroutine that hops threads across suspension.
    private val currentTrace = ThreadLocal<SpanBuffer?>()
    private var spanQueue: SpanQueue? = null
    private var metricBuffer: MetricBuffer? = null
    private var infrastructureMetricBuffer: MetricBuffer? = null
    private var changeQueue: ChangeQueue? = null

    // Set once the "not sending from this environment" warning has printed, so it prints once
    // per process.
    private val notSendingWarned = AtomicBoolean()

    /**
     * Prints [Configuration.notSendingWarning] at most once (tracked by [warned]): through the
     * configured logger when there is one, otherwise to [err], since a DSN that quietly sends
     * nothing is the one setup mistake a new install can't otherwise see. Silent without a DSN,
     * and never throws into the caller.
     */
    internal fun warnIfNotSending(config: Configuration, warned: AtomicBoolean, err: PrintStream) {
        try {
            val warning = config.notSendingWarning ?: return
            if (!warned.compareAndSet(false, true)) return
            if (config.logger != null) config.log(warning) else err.println("[ForgeOps] $warning")
        } catch (e: RuntimeException) {
            // A logger that throws is never a reason to fail init.
        }
    }

    fun configuration(): Configuration = synchronized(lock) {
        _configuration ?: Configuration().also { _configuration = it }
    }

    /** Installs with defaults / whatever `FORGE_OPS_*` environment variables are set. */
    fun init(installUncaughtExceptionHandler: Boolean = true, configurer: (Configuration) -> Unit = {}) {
        synchronized(lock) {
            configurer(configuration())
            warnIfNotSending(configuration(), notSendingWarned, System.err)
            reporter = Reporter(configuration())
            if (installUncaughtExceptionHandler) {
                installUncaughtExceptionHandlerChain()
            }
        }
    }

    /**
     * The affected user defaults to whatever [setUser] last set for the current thread; the
     * current thread's breadcrumb trail (see [addBreadcrumb]) is always attached automatically,
     * the same way [setUser]'s own value already is, with no separate argument needed for it.
     * Inside a [trace], the event also carries that trace's id (see [currentTraceId]).
     */
    fun captureException(error: Throwable, context: Map<String, Any?> = emptyMap(), user: Map<String, Any?>? = currentUser.get()) {
        reporter().report(error, context, user, currentBreadcrumbs.get().all(), currentTraceId())
    }

    /**
     * Sends every reported error and recorded change that is still waiting on its background
     * delivery coroutine, blocking for at most [timeout] (5 seconds by default) for both together.
     * A JVM shutdown hook already does this when the program exits normally (returning from `main`,
     * `exitProcess`/`System.exit`, or an uncaught exception), so a CLI tool or cron job needs
     * nothing more; call it yourself before `Runtime.halt`, or whenever you need them sent before
     * going on. Returns false if the timeout ran out first.
     */
    fun flush(timeout: Duration = DeliveryQueue.DRAIN_TIMEOUT): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        val errorsSent = reporter().flush(timeout)
        // Whatever the errors left of the one budget, not a fresh [timeout] of its own.
        val remaining = Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))
        val changesSent = synchronized(lock) { changeQueue }?.drain(remaining) ?: true
        return errorsSent && changesSent
    }

    /**
     * Manually attaches an affected user to whatever gets reported for the rest of this thread
     * (a servlet request, a background worker): there's no automatic Servlet/Spring auth
     * detection yet, so call this from your own filter/interceptor, wherever the current user is
     * available. Passing an empty map (or `null`) clears whatever was set, e.g. at the end of a
     * request.
     */
    fun setUser(user: Map<String, Any?>?) {
        if (user.isNullOrEmpty()) currentUser.remove() else currentUser.set(user)
    }

    /**
     * Records one entry into the current thread's breadcrumb trail: a query, an outbound call, or
     * anything worth remembering right up to the moment something actually goes wrong. A no-op,
     * not an exception, when [Configuration.trackBreadcrumbs] is `false`.
     *
     * This client has no web framework integration of its own (unlike `sdks/java`'s own Servlet
     * filter), so there's no automatic breadcrumb source and no filter to start a fresh trail per
     * request on its own: call [clearBreadcrumbs] yourself at the start of each request, the same
     * place you'd already be calling [setUser] from, or entries from an earlier request handled
     * on a reused thread will bleed into this one's own report.
     */
    fun addBreadcrumb(message: String, category: String = "custom", level: String = "info", data: Map<String, Any?> = emptyMap()) {
        if (!configuration().trackBreadcrumbs) return
        currentBreadcrumbs.get().add(message, category, level, data)
    }

    /**
     * Clears the current thread's breadcrumb trail. See [addBreadcrumb]'s own doc for why calling
     * this yourself, at the start of each request, is this client's responsibility to ask of you
     * rather than something it can do on its own.
     */
    fun clearBreadcrumbs() {
        currentBreadcrumbs.remove()
    }

    /**
     * Records one timed call's duration, in milliseconds, under [transactionName]: tallied
     * in-process (count, total, max) and flushed periodically as one small aggregate report, for
     * the Performance page's per-transaction table, not one network call per call. A no-op when
     * [Configuration.trackPerformance] is `false` or reporting isn't enabled for this environment.
     *
     * This client has no web framework integration of its own (unlike `sdks/java`'s servlet
     * performance filter), so nothing is timed automatically: wrap whatever you want on the
     * Performance page yourself, e.g. a request handler, with [timeTransaction], or call this
     * directly with a duration you measured. Keep [transactionName] low-cardinality
     * (`"GET /users/:id"`, not `"GET /users/42"`): every distinct name is its own row.
     */
    fun recordPerformance(transactionName: String, durationMs: Double) {
        val config = configuration()
        if (!config.trackPerformance || !config.isEnabled) return
        performanceFlusher().record(transactionName, durationMs)
    }

    /**
     * Runs [block], records how long it took under [transactionName] (see [recordPerformance]), and
     * returns whatever [block] returned. Recorded even if [block] throws: the duration up to the
     * throw is still a real duration, and a handler that fails is exactly one worth seeing on the
     * Performance page.
     *
     *     val user = ForgeOpsTracker.timeTransaction("GET /users/:id") { loadUser(id) }
     */
    inline fun <T> timeTransaction(transactionName: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            recordPerformance(transactionName, (System.nanoTime() - startedAt) / 1_000_000.0)
        }
    }

    /**
     * Delivers whatever has been tallied so far right now, instead of waiting for the next
     * [Configuration.performanceFlushInterval] tick. A JVM shutdown hook already does this on a
     * normal exit; call it yourself only if you need the data sent sooner than that.
     */
    fun flushPerformance() {
        performanceFlusher?.flush()
    }

    /**
     * Runs [block] as a trace named [rootName] (for example `"GET /checkout"` or `"job:reindex"`):
     * every [span] and [recordSpan] inside it nests beneath this root. When [block] took at least
     * [Configuration.traceCaptureThreshold] (1 second by default) the whole trace is sent, so fast
     * calls cost nothing on the wire. Sent even if [block] throws, which is rethrown unchanged.
     * Called inside an already-open trace it just records a span instead.
     *
     * This client has no web framework integration, so nothing starts a trace automatically: wrap
     * whatever you want traced, typically a request handler or a background job. To continue a
     * trace another service started, pass its `traceparent` header to the overload below.
     */
    inline fun <T> trace(rootName: String, block: () -> T): T = trace(rootName, null, block)

    /**
     * Same as [trace], continuing the caller's trace when [traceparent] is a usable W3C
     * `traceparent` header value, typically the incoming request's own header: the trace keeps the
     * caller's trace id, and its root span records the caller's span as its parent, so it nests
     * under that span on ForgeOps. `null`, blank, or malformed starts a fresh trace, exactly like
     * [trace]. Ignored inside an already-open trace.
     *
     *     ForgeOpsTracker.trace("POST /orders", request.getHeader("traceparent")) { handle(request) }
     */
    inline fun <T> trace(rootName: String, traceparent: String?, block: () -> T): T {
        if (!beginTrace(traceparent)) return span(rootName, "service", emptyMap(), block)
        val startedAt = java.time.Instant.now()
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            finishTrace(rootName, startedAt, (System.nanoTime() - started) / 1_000_000.0)
        }
    }

    /**
     * Times [block] as a child span of whatever span is open on this thread (or of the trace's
     * root). Outside a [trace] it just runs [block]. Recorded even if [block] throws.
     *
     *     val order = ForgeOpsTracker.span("charge card", "service", mapOf("orderId" to id)) { gateway.charge(id) }
     */
    inline fun <T> span(name: String, kind: String = "service", data: Map<String, Any?> = emptyMap(), block: () -> T): T {
        val id = openSpan() ?: return block()
        val startedAt = java.time.Instant.now()
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            closeSpan(id, name, kind, startedAt, (System.nanoTime() - started) / 1_000_000.0, spanData(kind, data, null, null))
        }
    }

    /**
     * Same as [span], for a `"database"` span that also carries the SQL it ran (a local SQLite
     * query, say) and which database it was ([dbSystem], such as `"sqlite"`): sent in the span's
     * data as `db.statement`, with every string and number literal replaced by `?` first (so values
     * never leave the process) and cut at 4000 characters, and `db.system`, lowercased. Both are
     * ignored on any other kind.
     *
     *     val rows = ForgeOpsTracker.span("Load orders", "database", statement = sql, dbSystem = "sqlite") { db.query(sql) }
     */
    inline fun <T> span(
        name: String,
        kind: String = "service",
        data: Map<String, Any?> = emptyMap(),
        statement: String?,
        dbSystem: String? = null,
        block: () -> T,
    ): T {
        val id = openSpan() ?: return block()
        val startedAt = java.time.Instant.now()
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            closeSpan(id, name, kind, startedAt, (System.nanoTime() - started) / 1_000_000.0, spanData(kind, data, statement, dbSystem))
        }
    }

    /**
     * Times an outgoing HTTP call as an `http` span named `"<method> <host>"` (never the path or
     * query, which can carry ids or tokens) and hands [block] the headers to add to that request:
     * a W3C `traceparent` whose parent id is this span's own id, so the called service's root span
     * nests under it when it continues the trace. Add them however your HTTP client does:
     *
     *     val response = ForgeOpsTracker.httpSpan("POST", uri) { headers ->
     *         val request = HttpRequest.newBuilder(uri).POST(body)
     *         headers.forEach { (name, value) -> request.header(name, value) }
     *         httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
     *     }
     *
     * The map is empty outside a [trace], when [Configuration.propagateTraces] is off, or when the
     * host isn't in [Configuration.tracePropagationTargets]; outside a trace no span is recorded
     * either. Recorded even if [block] throws, which is rethrown unchanged.
     */
    inline fun <T> httpSpan(method: String, url: java.net.URI, data: Map<String, Any?> = emptyMap(), block: (headers: Map<String, String>) -> T): T {
        val host = url.host
        val id = openHttpSpan() ?: return block(emptyMap())
        val startedAt = java.time.Instant.now()
        val started = System.nanoTime()
        try {
            return block(traceHeaders(id, host))
        } finally {
            closeSpan(id, "${method.uppercase()} ${host ?: "unknown"}", "http", startedAt, (System.nanoTime() - started) / 1_000_000.0, data)
        }
    }

    /** Same as the [java.net.URI] overload; a [url] that doesn't parse is treated as having no host. */
    inline fun <T> httpSpan(method: String, url: String, data: Map<String, Any?> = emptyMap(), block: (headers: Map<String, String>) -> T): T =
        httpSpan(method, parseUri(url), data, block)

    /** The id of the trace open on this thread (32 lowercase hex characters), or `null` outside a [trace]. */
    fun currentTraceId(): String? = currentTrace.get()?.traceId

    /** Records a span you timed yourself under the current one; a no-op outside a [trace]. */
    fun recordSpan(name: String, kind: String, startedAt: java.time.Instant, durationMs: Double, data: Map<String, Any?> = emptyMap()) {
        currentTrace.get()?.recordLeaf(name, kind, startedAt, durationMs, spanData(kind, data, null, null))
    }

    /** Same as [recordSpan], with a `"database"` span's SQL and database system, sent as described on the [span] that takes them. */
    fun recordSpan(
        name: String,
        kind: String,
        startedAt: java.time.Instant,
        durationMs: Double,
        data: Map<String, Any?> = emptyMap(),
        statement: String?,
        dbSystem: String? = null,
    ) {
        currentTrace.get()?.recordLeaf(name, kind, startedAt, durationMs, spanData(kind, data, statement, dbSystem))
    }

    /**
     * A span's data, with a `"database"` span's SQL added as `db.statement` (masked) and its
     * `db.system`. A `db.statement` passed in [data] directly is masked too, so raw SQL can never go
     * out on a span.
     */
    @PublishedApi
    internal fun spanData(kind: String, data: Map<String, Any?>, statement: String?, dbSystem: String?): Map<String, Any?> {
        if (kind != "database") return data
        val result = LinkedHashMap(data)
        val raw = statement ?: result["db.statement"] as? String
        result.remove("db.statement")
        val system = (dbSystem ?: result["db.system"] as? String)?.trim()
        SqlStatement.mask(raw, system)?.let { result["db.statement"] = it }
        result.remove("db.system")
        if (!system.isNullOrEmpty()) result["db.system"] = system.lowercase()
        return result
    }

    // Kept for code compiled against an earlier version, where trace's inlined body calls this.
    @PublishedApi
    internal fun beginTrace(): Boolean = beginTrace(null)

    /**
     * False when a trace is already open on this thread; true otherwise, whether or not one started.
     * One starts whenever reporting is enabled, even with trackTracing off: its id still goes on
     * errors and outgoing headers, and finishTrace just never sends its spans.
     */
    @PublishedApi
    internal fun beginTrace(traceparent: String?): Boolean {
        if (currentTrace.get() != null) return false
        val config = configuration()
        if (!config.isEnabled) return true
        val incoming = TraceParent.parse(traceparent)
        currentTrace.set(
            if (incoming != null) SpanBuffer(config, incoming.traceId, incoming.parentSpanId) else SpanBuffer(config),
        )
        return true
    }

    @PublishedApi
    internal fun finishTrace(rootName: String, startedAt: java.time.Instant, durationMs: Double) {
        val buffer = currentTrace.get()
        currentTrace.remove()
        if (!configuration().trackTracing) return
        val payload = buffer?.finishTrace(rootName, startedAt, durationMs) ?: return
        spanQueue().push(payload)
    }

    @PublishedApi
    internal fun openSpan(): String? = currentTrace.get()?.open()

    // Generates the span id up front, before the call is made, so the header can name the span
    // the call is later recorded as.
    @PublishedApi
    internal fun openHttpSpan(): String? = currentTrace.get()?.open(TraceParent.generateSpanId())

    @PublishedApi
    internal fun traceHeaders(spanId: String, host: String?): Map<String, String> {
        val buffer = currentTrace.get() ?: return emptyMap()
        if (!configuration().shouldPropagateTrace(host)) return emptyMap()
        return mapOf(TraceParent.HEADER to TraceParent.build(buffer.traceId, spanId))
    }

    @PublishedApi
    internal fun parseUri(url: String): java.net.URI = try {
        java.net.URI(url)
    } catch (e: java.net.URISyntaxException) {
        java.net.URI("")
    }

    @PublishedApi
    internal fun closeSpan(id: String, name: String, kind: String, startedAt: java.time.Instant, durationMs: Double, data: Map<String, Any?>) {
        currentTrace.get()?.finish(id, name, kind, startedAt, durationMs, data)
    }

    // The two metric buffers, created together on first use: independent of each other (a program
    // may only ever call one), but cheap enough that creating both is simpler than tracking which.
    private fun metricBuffers(): Pair<MetricBuffer, MetricBuffer> = synchronized(lock) {
        val config = configuration()
        val client = Client(config)
        val custom = metricBuffer
            ?: MetricBuffer(config, { config.metricFlushInterval }) { entries -> client.outcome { client.deliverMetrics(entries) } }
                .also { metricBuffer = it }
        val infrastructure = infrastructureMetricBuffer
            ?: MetricBuffer(config, { config.infrastructureMetricFlushInterval }) { entries ->
                client.outcome { client.deliverInfrastructureMetrics(entries) }
            }.also { infrastructureMetricBuffer = it }
        custom to infrastructure
    }

    /**
     * Records a named business metric (a signup, a payment, anything you want to name), buffered and
     * flushed periodically as one batch rather than one network call per capture. [value] defaults to
     * 1.0 so a bare counter-style call needs no argument; pass one for a real magnitude
     * (`captureMetric("payment", 49.0)`); it may be negative (a refund). A no-op when the client isn't
     * enabled (no DSN, or this environment isn't in `enabledEnvironments`), and a NaN or infinite
     * value is dropped.
     */
    fun captureMetric(name: String, value: Double = 1.0) {
        val config = configuration()
        if (!config.isEnabled) return
        metricBuffers().first.record(
            mapOf("metric_name" to name, "value" to value, "environment" to config.environment, "release" to config.release),
        )
    }

    /**
     * Records one infrastructure reading (CPU, memory, disk, anything else a program of yours reads)
     * from one of your own hosts. [hostname] defaults to [Configuration.serverName], so a script
     * running on the box it reports about needs no argument. Same buffered-batch delivery and
     * no-op-when-disabled contract as [captureMetric]. A JVM shutdown hook flushes what is left when
     * the program exits normally, so a short-lived cron job needs nothing more; call [flushMetrics] if
     * it ends with `Runtime.halt`.
     */
    fun captureInfrastructureMetric(name: String, value: Double, hostname: String? = null) {
        val config = configuration()
        if (!config.isEnabled) return
        metricBuffers().second.record(mapOf("metric_name" to name, "value" to value, "hostname" to (hostname ?: config.serverName)))
    }

    /** Delivers every buffered metric and infrastructure reading right now, instead of waiting for the next flush interval. */
    fun flushMetrics() {
        val (custom, infrastructure) = synchronized(lock) { metricBuffer to infrastructureMetricBuffer }
        custom?.flush()
        infrastructure?.flush()
    }

    /**
     * Records one change to what your program is running: a feature flag flipped, a remote config
     * value updated, anything that could explain a shift in errors or performance. ForgeOps shows it
     * on the timeline next to the errors around it. [kind] is one of [Change.KINDS] (anything else is
     * sent as `"other"`); [title] is required and cut to 200 characters. [details] is a small
     * JSON-shaped map, [environment] defaults to [Configuration.environment], [url] must be http(s),
     * and [id] is an idempotency key.
     *
     * Returns immediately: the change is queued and sent by a background coroutine, never on the
     * calling thread, and nothing here ever throws, whether the request fails or the plan doesn't
     * include change tracking. A no-op when the client isn't enabled.
     *
     *     flags.addListener { key, old, new ->
     *         ForgeOpsTracker.recordChange("feature_flag", "$key turned ${if (new) "on" else "off"}", mapOf("key" to key, "from" to old, "to" to new))
     *     }
     */
    fun recordChange(
        kind: String,
        title: String,
        details: Map<String, Any?>? = null,
        environment: String? = null,
        service: String? = null,
        actor: String? = null,
        url: String? = null,
        id: String? = null,
        occurredAt: java.time.Instant? = null,
    ) {
        try {
            val config = configuration()
            if (!config.isEnabled) return
            val payload = Change.payload(config, kind, title, details, environment, service, actor, url, id, occurredAt)
            if (payload == null) {
                config.log("dropped a change with no title")
                return
            }
            changeQueue().push(payload)
        } catch (e: Exception) {
            configuration().log("recordChange failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun changeQueue(): ChangeQueue = synchronized(lock) {
        changeQueue ?: ChangeQueue(configuration()).also { changeQueue = it }
    }

    private fun spanQueue(): SpanQueue = synchronized(lock) {
        spanQueue ?: SpanQueue(configuration()).also { spanQueue = it }
    }

    private fun performanceFlusher(): PerformanceFlusher = synchronized(lock) {
        performanceFlusher ?: PerformanceFlusher(configuration(), Client(configuration())).also { performanceFlusher = it }
    }

    private fun reporter(): Reporter = synchronized(lock) {
        reporter ?: Reporter(configuration()).also { reporter = it }
    }

    private fun installUncaughtExceptionHandlerChain() {
        if (installedHandler) return
        installedHandler = true
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            captureException(error, mapOf("thread" to thread.name))
            val previous = previousHandler
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                System.err.println("Exception in thread \"${thread.name}\" ${stackTraceString(error)}")
            }
        }
    }

    private fun stackTraceString(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    /**
     * Test-only: discards all configured/installed state so each test starts clean, restoring
     * whatever default uncaught-exception handler was in place before [init] was ever called. The
     * same `resetForTesting()`/`_resetForTesting()` convention every other client in this repo
     * uses.
     */
    fun resetForTesting() = synchronized(lock) {
        if (installedHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        performanceFlusher?.shutdown()
        performanceFlusher = null
        spanQueue?.shutdown()
        spanQueue = null
        changeQueue?.shutdown()
        changeQueue = null
        metricBuffer?.shutdown()
        infrastructureMetricBuffer?.shutdown()
        metricBuffer = null
        infrastructureMetricBuffer = null
        _configuration = null
        reporter = null
        previousHandler = null
        installedHandler = false
        currentUser.remove()
        currentBreadcrumbs.remove()
        currentTrace.remove()
    }
}
