package io.github.lukepopwell.tracker

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

/**
 * A single DSN string carries both the ingestion URL and the project's API key:
 * `https://<api_key>@host/api/v1/events`. Mirrors `sdks/java`'s own `Configuration` (itself
 * mirroring `ForgeOpsTracker::Configuration` in the Rails client gem): see that gem's README for
 * the shared design rationale.
 */
class Configuration {
    var dsn: String? = System.getenv("FORGE_OPS_DSN")
        set(value) {
            field = value
            parsedDsn = null
            parsedDsnAttempted = false
        }

    /**
     * `FORGE_OPS_ENVIRONMENT` when set, otherwise `"production"`: a process given a DSN and
     * nothing else is assumed to be one you want errors from. Set
     * `FORGE_OPS_ENVIRONMENT=development` (or assign this) where it shouldn't send.
     */
    var environment: String = resolveEnvironment(System::getenv)
    var release: String? = System.getenv("FORGE_OPS_RELEASE")
    var serverName: String? = safeHostname()

    /**
     * Package prefix (e.g. `"com.acme.myapp"`) used to mark backtrace frames as `in_app`. Unset
     * by default: deliberately mirrors the Rails gem's behavior when `app_root` is unset (every
     * frame reported as not-in-app, the safe default), since there's no safe way to guess a
     * caller's top-level package automatically.
     *
     * Unlike the Ruby/Python/PHP/Node clients, this can't be file-path-based:
     * [StackTraceElement.getFileName] is always a bare file name (`"MyClass.kt"`), never a full
     * path: the same reason `sdks/java`'s own `Configuration` uses a package prefix instead of
     * a path comparison, and this client shares that class's `StackTraceElement`-based backtrace
     * entirely (a Kotlin exception is a JVM `Throwable` under the hood).
     */
    var appPackagePrefix: String? = null

    var enabledEnvironments: Set<String> = setOf("production", "staging")
    var queueSize: Int = 1000
    var connectTimeout: Duration = Duration.ofSeconds(2)
    var readTimeout: Duration = Duration.ofSeconds(2)
    var scrubPii: Boolean = true

    /**
     * Whether [EventBuilder] reads a few lines of source off disk around each in-app frame's
     * culprit line (see `EventBuilder.attachSourceContext`). Defaults on so a snippet shows up
     * without any extra setup, but this flag alone isn't the real protection against literal
     * source code leaving this process: every ForgeOps project has its own server-side setting
     * that's the durable, enforced switch, since it applies no matter what this flag happens to
     * be set to on any given deployment. Turn this off here too if this host app should never
     * even attempt that disk read in the first place.
     */
    var captureSourceContext: Boolean = true

    /**
     * When an error comes from a database call (Hibernate's `JDBCException`, Spring's
     * `BadSqlGrammarException`, SQLite's `while compiling:` text, and anything that wraps one),
     * send the names of the stored procedure, table and view its SQL touched, so an issue says
     * where to start looking. Names are identifiers, never values, which is why this defaults on.
     * [captureSqlStatement] is the separate, opt-in step of also sending the statement itself,
     * with every string and number replaced by `?`; off by default because even a masked
     * statement describes your schema, and ForgeOps' own per-project setting is what durably
     * governs whether the server stores it.
     */
    var captureSqlObjects: Boolean = true

    var captureSqlStatement: Boolean = false

    /**
     * Strict privacy, for apps that can't let personal or health data leave their own servers:
     * events carry only structured facts (exception class, stack frames, release, environment),
     * never free text. The message is replaced by `message_fingerprint` (see
     * [StrictPrivacy.messageFingerprint]), breadcrumbs aren't recorded or sent, `sql_statement` and
     * source context aren't sent, the user is sent as its id only, and context and tags keep only
     * the [privacyAllowedFields] with plain values. Spans keep only their method and host for
     * outbound calls, and only `db.statement` and `db.system` as data. Off by default;
     * `FORGE_OPS_STRICT_PRIVACY=true` turns it on.
     */
    var strictPrivacy: Boolean = resolveStrictPrivacy(System::getenv)

    /**
     * The context and tag keys strict privacy lets through anyway (your own error codes, a feature
     * name), when their value is a string, number or boolean. Empty by default;
     * `FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature` sets it from the environment.
     */
    var privacyAllowedFields: Set<String> = resolvePrivacyAllowedFields(System::getenv)

    /**
     * Whether [ForgeOpsTracker.addBreadcrumb] actually records anything, and whether
     * [ForgeOpsTracker.captureException] attaches the current thread's trail to a report at all:
     * `addBreadcrumb` itself never throws when this is `false`, it just becomes a no-op, the same
     * "the call site never has to check first" posture every other independently-gated mechanism
     * in this client already has. On by default.
     */
    var trackBreadcrumbs: Boolean = true

    /**
     * The most recent entries a single thread's trail keeps; the oldest is dropped once full.
     * Matches `gems/forge_ops_tracker`'s own default exactly.
     */
    var maxBreadcrumbs: Int = 30

    /**
     * Whether [ForgeOpsTracker.recordPerformance]/[ForgeOpsTracker.timeTransaction] time anything
     * at all. On by default, the same "on unless you turn it off" posture error reporting itself
     * already has. This client has no web framework integration, so nothing is timed
     * automatically: this only gates the manual API.
     */
    var trackPerformance: Boolean = true

    /**
     * How often the in-process tallies are flushed as one small aggregate report, rather than one
     * network call per timed call. Matches `gems/forge_ops_tracker`'s own default (60s).
     */
    var performanceFlushInterval: Duration = Duration.ofSeconds(60)

    /**
     * Intervals between flushes of the buffered [ForgeOpsTracker.captureMetric] /
     * [ForgeOpsTracker.captureInfrastructureMetric] entries (see [MetricBuffer]). There is no
     * `trackMetrics` flag the way [trackPerformance] has one: these are explicit calls the host app's
     * own code makes, not automatic instrumentation, so there is nothing to turn off that simply not
     * calling them doesn't already do.
     */
    var metricFlushInterval: Duration = Duration.ofSeconds(60)
    var infrastructureMetricFlushInterval: Duration = Duration.ofSeconds(60)

    /**
     * Whether [ForgeOpsTracker.trace] reports its trace (when slow) to `/spans`.
     * [ForgeOpsTracker.span] and [ForgeOpsTracker.recordSpan] only record inside a trace, so this
     * gates every span. With it off, a `trace` block still has a trace id, attached to errors
     * captured inside it and propagated by [ForgeOpsTracker.httpSpan] (see [propagateTraces]), since
     * that id is also what links an error here to one in another service. This client has no web
     * framework integration, so nothing starts a trace automatically.
     */
    var trackTracing: Boolean = true

    /** A trace is only sent when its root span took at least this long. */
    var traceCaptureThreshold: Duration = Duration.ofSeconds(1)

    /**
     * Whether [ForgeOpsTracker.httpSpan] hands its block a W3C `traceparent` header for the
     * outgoing call, so the service being called continues this trace. On by default, matching
     * `gems/forge_ops_tracker`: the header carries the trace id that links an error here to an
     * error there, which is useful with or without spans, so it goes out even with [trackTracing]
     * off.
     */
    var propagateTraces: Boolean = true

    /**
     * Which hosts get that header. `null` (the default) means every host. Otherwise a list of
     * host strings, each matching that host and its subdomains on a dot boundary (`"example.com"`
     * matches `"api.example.com"`, never `"badexample.com"`), and/or [Regex]es (or
     * `java.util.regex.Pattern`s) searched for anywhere in the host, so anchor them yourself.
     * Anything else in the list matches nothing. Useful for a third-party API that rejects unknown
     * headers, or that shouldn't learn your trace ids at all.
     */
    var tracePropagationTargets: List<Any>? = null

    /** Whether an outgoing call to [host] should carry a `traceparent` header; case-insensitive, since hostnames are. */
    fun shouldPropagateTrace(host: String?): Boolean {
        if (!propagateTraces) return false
        val targets = tracePropagationTargets ?: return true
        val normalized = host.orEmpty().lowercase()
        return targets.any { target ->
            when (target) {
                is Regex -> target.containsMatchIn(normalized)
                is java.util.regex.Pattern -> target.matcher(normalized).find()
                is String -> {
                    val domain = target.lowercase().removePrefix(".")
                    domain.isNotEmpty() && (normalized == domain || normalized.endsWith(".$domain"))
                }
                else -> false
            }
        }
    }

    /** Called with a debug-level message on delivery failures; `null` (the default) discards them. */
    var logger: ((String) -> Unit)? = null

    private var parsedDsn: URI? = null
    private var parsedDsnAttempted: Boolean = false

    val apiKey: String?
        get() = parsedDsn()?.userInfo

    /** The ingestion URL with credentials stripped out: they travel as the Authorization header instead. */
    val ingestionUri: URI?
        get() {
            val parsed = parsedDsn() ?: return null
            return try {
                URI(parsed.scheme, null, parsed.host, parsed.port, parsed.path, null, null)
            } catch (e: URISyntaxException) {
                null
            }
        }

    /**
     * Same derivation as [ingestionUri], with the trailing `/events` swapped for
     * `/performance_samples`: one DSN, two endpoints, matching `sdks/java`'s own
     * `getPerformanceSamplesUri`.
     */
    val performanceSamplesUri: URI?
        get() {
            val uri = ingestionUri ?: return null
            val path = uri.path
            if (path == null || !path.endsWith("/events")) return uri
            return try {
                URI(uri.scheme, null, uri.host, uri.port, path.removeSuffix("/events") + "/performance_samples", null, null)
            } catch (e: URISyntaxException) {
                null
            }
        }

    /** Same derivation again, swapping the trailing `/events` for `/custom_metrics`. */
    val customMetricsUri: URI?
        get() = swapEventsSuffix("/custom_metrics")

    /** Same derivation again, swapping the trailing `/events` for `/infrastructure_metrics`. */
    val infrastructureMetricsUri: URI?
        get() = swapEventsSuffix("/infrastructure_metrics")

    /** Same derivation again, swapping the trailing `/events` for `/changes`. */
    val changesUri: URI?
        get() = swapEventsSuffix("/changes")

    private fun swapEventsSuffix(replacement: String): URI? {
        val uri = ingestionUri ?: return null
        val path = uri.path
        if (path == null || !path.endsWith("/events")) return uri
        return try {
            URI(uri.scheme, null, uri.host, uri.port, path.removeSuffix("/events") + replacement, null, null)
        } catch (e: URISyntaxException) {
            null
        }
    }

    /** Same derivation again, swapping the trailing `/events` for `/spans`. */
    val spansUri: URI?
        get() {
            val uri = ingestionUri ?: return null
            val path = uri.path
            if (path == null || !path.endsWith("/events")) return uri
            return try {
                URI(uri.scheme, null, uri.host, uri.port, path.removeSuffix("/events") + "/spans", null, null)
            } catch (e: URISyntaxException) {
                null
            }
        }

    val isEnabled: Boolean
        get() = !dsn.isNullOrBlank() && !apiKey.isNullOrBlank() && environment in enabledEnvironments

    fun log(message: String) {
        logger?.invoke("[ForgeOpsTracker] $message")
    }

    private fun parsedDsn(): URI? {
        val currentDsn = dsn
        if (currentDsn.isNullOrBlank()) return null

        if (!parsedDsnAttempted) {
            parsedDsnAttempted = true
            parsedDsn = try {
                URI(currentDsn)
            } catch (e: URISyntaxException) {
                // A malformed DSN is a configuration mistake, not something that should ever
                // crash the host app: same "never throw back into the caller" guarantee as
                // every other client, including sdks/java's own identical handling.
                null
            }
        }
        return parsedDsn
    }

    /**
     * The explanation [ForgeOpsTracker.init] prints when this configuration has a DSN but its
     * environment isn't one of [enabledEnvironments], so nothing will be sent; null when there's
     * nothing to warn about (no DSN, or the environment is enabled).
     */
    val notSendingWarning: String?
        get() {
            if (dsn.isNullOrBlank() || apiKey.isNullOrBlank() || environment in enabledEnvironments) return null
            val enabled = if (enabledEnvironments.isEmpty()) {
                "no environments are enabled"
            } else {
                "only ${enabledEnvironments.joinToString(", ")} are enabled"
            }
            return "Not sending: this environment is \"$environment\", and $enabled. " +
                "Set FORGE_OPS_ENVIRONMENT=production (or add \"$environment\" to the enabled environments) " +
                "to send from here."
        }

    internal companion object {
        /**
         * The environment a fresh [Configuration] starts with, read through [getenv]:
         * `FORGE_OPS_ENVIRONMENT` when set and not blank, otherwise `"production"`, so a process
         * given a DSN and nothing else sends. No framework variable is consulted: Spring's
         * `SPRING_PROFILES_ACTIVE`, the nearest candidate, is a list of profiles rather than an
         * environment name.
         */
        fun resolveEnvironment(getenv: (String) -> String?): String =
            getenv("FORGE_OPS_ENVIRONMENT")?.takeIf { it.isNotBlank() } ?: "production"

        /** `FORGE_OPS_STRICT_PRIVACY`: `true` or `1` (any case) turns strict privacy on. */
        fun resolveStrictPrivacy(getenv: (String) -> String?): Boolean =
            getenv("FORGE_OPS_STRICT_PRIVACY")?.trim()?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: false

        /** `FORGE_OPS_PRIVACY_ALLOWED_FIELDS`: comma separated, blanks around each name ignored. */
        fun resolvePrivacyAllowedFields(getenv: (String) -> String?): Set<String> =
            getenv("FORGE_OPS_PRIVACY_ALLOWED_FIELDS").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        private fun safeHostname(): String? = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            null
        }
    }
}
