# tracker-kt

Kotlin error reporting client for [ForgeOps](https://getforgeops.net),
for JVM server-side apps. Requires JDK 17+ and Kotlin 2.0+. Captures uncaught exceptions
automatically with a JVM-wide handler, lets you report caught exceptions explicitly, and scrubs
likely personal data before anything leaves the process.

> For health or personal data, turn on [strict privacy](#strict-privacy): the SDK then sends only structured facts about each error (its type, stack trace, release and route), never the message, a raw path, breadcrumbs, or context you haven't allowed.

## Design: coroutines and a typed, null-safe API

This client is built with Kotlin idioms throughout: delivery runs through a bounded coroutine
`Channel` drained by a dedicated worker coroutine rather than a blocking thread, `Configuration`
is a typed, null-safe data holder, and the public API is exposed as a Kotlin `object` singleton.

## Installation

```xml
<dependency>
  <groupId>io.github.luke-popwell</groupId>
  <artifactId>tracker-kt</artifactId>
  <version>0.15.1</version>
</dependency>
```

The `groupId` has a hyphen; the actual Kotlin package (below) doesn't, since Kotlin identifiers
can't contain one: Sonatype Central's namespace verification is tied to the exact GitHub
username string (`io.github.luke-popwell`), which is what `groupId` has to match, while the
package name is free to be anything valid.

### Dependencies

One runtime dependency: `kotlinx-coroutines-core`, used for the delivery queue: a bounded
`Channel` plus a dedicated single-worker dispatcher. Everything else is JDK-only: JSON encoding
(`Json.kt`, ~40 lines, hand-rolled since the JDK has no built-in JSON support) and HTTP delivery
(`java.net.http.HttpClient`, built in since JDK 11) are both stdlib-based, no extra dependency
needed.

### Build tooling

This client builds with Maven, using the `kotlin-maven-plugin` to compile Kotlin sources.
Switching to Gradle later, if this SDK moves to its own repo, is a reasonable option but not a
requirement: Maven's Kotlin support is complete enough that there's no functional reason to
require Gradle.

## Configuration

Set a DSN (from a project's settings page in ForgeOps), either via the `FORGE_OPS_DSN` environment
variable or explicitly:

```kotlin
import io.github.lukepopwell.tracker.ForgeOpsTracker

ForgeOpsTracker.init { config ->
    config.dsn = "https://<api_key>@getforgeops.net/api/v1/events" // or leave unset to read FORGE_OPS_DSN
    config.release = "..."
    config.environment = "production"
}
```

Call `init` once at startup. By default this also installs a JVM-wide uncaught-exception handler
(see below); pass `installUncaughtExceptionHandler = false` to opt out.

### Environments

The environment comes from `config.environment`, or the `FORGE_OPS_ENVIRONMENT` environment
variable, and is `"production"` when neither is set, so a DSN alone is enough to start sending.
Errors are only sent from the enabled environments (`config.enabledEnvironments`, default
`production` and `staging`). On a machine that shouldn't send, such as a laptop, set
`FORGE_OPS_ENVIRONMENT=development`. When a DSN is configured but the environment isn't enabled,
`init` says so once, through `config.logger` if you set one and on `stderr` otherwise:

```
[ForgeOps] Not sending: this environment is "development", and only production, staging are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add "development" to the enabled environments) to send from here.
```

## What gets reported automatically, and what doesn't

**An uncaught exception on any thread needs no further wiring at all.** Installing a
`Thread.UncaughtExceptionHandler` via `Thread.setDefaultUncaughtExceptionHandler` does not change
whether or how the JVM exits: verified directly against a real uncaught throw: a thread that
dies from an uncaught exception dies either way, and the process still exits the same way whether
or not a handler is installed. The one real side effect is narrower: installing a handler replaces
the JVM's own default behavior of printing `"Exception in thread ..."` plus the stack trace to
`stderr`. This client's handler restores that: it chains to whatever default handler was already
installed, if any, and otherwise reproduces the JVM's own default printed-to-stderr output itself,
so installing this client changes nothing observable about an uncaught exception besides also
reporting it.

**An exception your own code catches and handles is different**: report it explicitly, right at
the catch site:

```kotlin
import io.github.lukepopwell.tracker.ForgeOpsTracker

try {
    chargeCard(order)
} catch (e: CardDeclinedException) {
    ForgeOpsTracker.captureException(e, mapOf("order_id" to order.id))
    logger.warn("card declined", e)
}
```

Delivery happens on a background coroutine with a bounded queue and a short per-request HTTP
timeout (`Configuration.readTimeout`, 2s default). Every failure mode: network errors, timeouts,
a full queue, a malformed DSN: is caught and dropped rather than thrown, so a broken or
unreachable tracker can never take down the host app.

### Short-lived programs (a CLI tool, a cron job)

A program that reports an error or records a change and then ends needs nothing more: the first
report (or change) registers a JVM shutdown hook that sends whatever is still queued before the JVM
exits, whether `main` returns, calls `exitProcess`, or dies from an uncaught exception. It waits at
most 5 seconds in all, and a program that never reported or recorded anything doesn't wait at all.
`Runtime.halt` skips shutdown hooks, so call `ForgeOpsTracker.flush()` first if your program ends
that way; it sends queued errors and changes alike:

```kotlin
import io.github.lukepopwell.tracker.ForgeOpsTracker

ForgeOpsTracker.captureException(e)
ForgeOpsTracker.flush() // sends what's queued, waiting at most 5 seconds; flush(Duration) to choose
Runtime.getRuntime().halt(1)
```

## Identifying users

```kotlin
ForgeOpsTracker.captureException(error, user = mapOf("id" to user.id, "email" to user.email))
```

Or `setUser(Map<String, Any?>?)` to attach it for the rest of this thread (a servlet request, a
background worker) rather than passing it to every `captureException()` call by hand, e.g. from
your own filter/interceptor (there's no automatic Servlet/Spring auth detection yet, so this is
manual either way):

```kotlin
ForgeOpsTracker.setUser(mapOf("id" to request.userPrincipal.name))
```

A `ThreadLocal`, the direct Kotlin/JVM analog to `Thread.current` in `gems/forge_ops_tracker`'s own
equivalent, and the correct isolation boundary for a typical servlet container's
one-thread-per-request model. Passing an empty map (or `null`) clears whatever was set, e.g. at
the end of a request. Shows up on an issue's own detail page, and as its own affected-users count
alongside the regular event count.

## Breadcrumbs

A bounded, ordered trail of what happened right before an error: on by default, capped at the 30
most recent entries per thread, both configurable:

```kotlin
ForgeOpsTracker.configuration().apply {
    trackBreadcrumbs = false // opt out entirely
    maxBreadcrumbs = 50      // default 30
}
```

```kotlin
ForgeOpsTracker.addBreadcrumb("charged card", data = mapOf("order_id" to order.id))
```

`category` defaults to `"custom"` and `level` to `"info"`; `captureException` (an explicit call,
or the installed uncaught-exception handler) attaches the current thread's trail automatically,
the same way it already attaches whatever `setUser` last set, with no separate argument needed.

A `ThreadLocal`, the same choice `setUser` above already made and for the identical reason. This
client has no web framework integration of its own yet (unlike `sdks/java`'s own Servlet filter,
confirmed by reading every source file first), so there's no automatic breadcrumb source and no
filter to start a fresh trail per request on its own. Call `clearBreadcrumbs()` yourself at the
start of each request, the same place you'd already be calling `setUser` from:

```kotlin
ForgeOpsTracker.clearBreadcrumbs()
```

Without this, a reused servlet thread would let one request's trail bleed into the next one
handled on it.

## Performance monitoring

Times whatever you wrap and reports one small aggregate per transaction (how many times it ran,
total and maximum duration) every `performanceFlushInterval` (60s by default), for the Performance
page's per-transaction table. Not one network call per timed call.

Each aggregate also carries a small latency histogram (a count per fixed latency bucket: 50, 100,
250, 500, 1000, 2500, 5000 and 10000ms, plus an overflow bucket), so ForgeOps can show an
approximate p50/p95/p99 per transaction, not just an average. Percentiles are accurate to the width
of whichever bucket a duration falls into; the SDK never stores the individual durations.

```kotlin
// Wrap a whole request handler, or any block you want on the Performance page:
val response = ForgeOpsTracker.timeTransaction("GET /users/:id") { handleRequest(request) }

// Or record a duration you measured yourself:
ForgeOpsTracker.recordPerformance("nightly-export", elapsed.toMillis().toDouble())
```

This client has no web framework integration (unlike `sdks/java`'s servlet performance filter), so
**nothing is timed automatically**: you choose what to wrap. Keep transaction names low-cardinality
(`"GET /users/:id"`, not `"GET /users/42"`): every distinct name is its own row. `timeTransaction`
records even if the block throws. Turn it off with `trackPerformance = false`; it also does nothing
(and starts no thread) when reporting isn't enabled for the current environment. A JVM shutdown hook
flushes the last partial window on a normal exit; `flushPerformance()` sends it sooner.

A delivery that fails for now (a network error, a timeout, a 5xx, a 408 or a 429) keeps every tally,
so the next flush's window just grows. A batch the server rejects (any other 4xx) is dropped and
never sent again, and so is a batch whose delivery has failed five flushes in a row. What a flush
delivered is *subtracted* from the tallies afterward, never the whole map cleared: a `recordPerformance` call
that lands while a delivery is in flight would otherwise be silently discarded (a real bug `sdks/go`
had and fixed; `sdks/java`'s and `gems/forge_ops_tracker`'s flushers still have it). A deterministic
test pins this.

## Distributed tracing

A slow call's own breakdown: which database calls, HTTP calls, or pieces of your code the time
went to, shown as a span tree on ForgeOps. Wrap the unit of work in `trace`, and anything inside it
can add spans; the trace is sent only when the whole thing took at least `traceCaptureThreshold`
(1 second by default), so fast calls cost nothing on the wire. A trace can also be followed into
the services you call and continued from the service that called you (see "Following a request
across services" below).

```kotlin
val response = ForgeOpsTracker.trace("GET /checkout") {
    val order = ForgeOpsTracker.span("load order", "database", mapOf("orderId" to id)) { repo.find(id) }
    ForgeOpsTracker.span("charge card") { gateway.charge(order) } // kind defaults to "service"
    render(order)
}

// Something you timed yourself (kind is one of controller/service/database/redis/http/job/other):
ForgeOpsTracker.recordSpan("SELECT orders", "database", startedAt, durationMs)
```

This client has no web framework integration, so **nothing starts a trace or records a span
automatically**: you choose what to wrap. `span` nests under whichever span is open on the same
thread, records even when the block throws (rethrowing it unchanged), and simply runs the block
outside a `trace`; a `trace` inside another `trace` records a span instead. The open trace is a
`ThreadLocal`, like the breadcrumb trail, so it follows blocking code but not a coroutine that
switches threads across a suspension point. A trace holds at most 500 spans. Turn span reporting
off with `trackTracing = false`.

### Database spans with their SQL

A `database` span can carry the SQL it ran and which database it was. Every string and number
literal is replaced by `?` before it leaves your process (so `WHERE email = 'a@b.co'` is sent as
`WHERE email = ?`), the statement is cut at 4000 characters, and ForgeOps masks it again on arrival.
It is sent in the span's data as `db.statement` and `db.system`, and ForgeOps shows it on the span.
Both are ignored on any other kind. This client doesn't instrument a database driver itself, so pass
the statement where you run the query:

```kotlin
val sql = "SELECT * FROM orders WHERE customer_id = 42 AND status = 'open'"
val orders = ForgeOpsTracker.span("Load orders", "database", statement = sql, dbSystem = "postgresql") {
    connection.createStatement().use { it.executeQuery(sql).toOrders() }
}
// Sent as db.statement "SELECT * FROM orders WHERE customer_id = ? AND status = ?", db.system "postgresql".

// Or for a query you timed yourself:
ForgeOpsTracker.recordSpan("Load orders", "database", startedAt, durationMs, statement = sql, dbSystem = "postgresql")
```

Masking covers backslash-escaped quotes (`'o\'brien'`), prefixed strings (`E'...'`, `X'DEADBEEF'`,
`N'...'`, `B'...'`, `U&'...'`), and hex, binary, exponent and leading-dot numbers (`0x1F`, `0b101`,
`1.5E-3`, `.5`), never the digits inside a name like `orders2` or a `$1` placeholder. With
`dbSystem` `"mysql"` or `"mariadb"`, double-quoted text is masked too, since those databases read
`"..."` as a string; for any other database it's a name and is left as is.

### Following a request across services

Traces use the [W3C Trace Context](https://www.w3.org/TR/trace-context/) standard (a `traceparent`
header), so an error or a slow call can be followed from one service into the next.

**Outgoing**: wrap each HTTP call you make inside a trace in `httpSpan`. It records the call as an
`http` span named after the method and host (never the path or query) and hands your block the
headers to add; the header's parent id is that span's own id, so the called service's spans nest
under it:

```kotlin
ForgeOpsTracker.trace("POST /checkout") {
    val response = ForgeOpsTracker.httpSpan("POST", paymentsUri) { headers ->
        val request = HttpRequest.newBuilder(paymentsUri).POST(BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
    // ...
}
```

`httpSpan` takes a `java.net.URI` or a `String`, works with any HTTP client (the headers are a
plain `Map<String, String>`), records the span even when the call throws, and rethrows unchanged.
Outside a `trace` it records nothing and the map is empty.

**Incoming**: pass the request's own `traceparent` header to `trace` and it continues the caller's
trace (same trace id, root span parented under the caller's span). A missing or malformed value just
starts a new trace:

```kotlin
ForgeOpsTracker.trace("POST /orders", request.getHeader("traceparent")) { handle(request) }
```

Every error captured inside a `trace` carries that trace's id (`ForgeOpsTracker.currentTraceId()`
returns it too, for your own logs), so ForgeOps can show it next to errors from the other services
that handled the same request. Errors captured outside a trace are unchanged. The trace id and the
header exist even with `trackTracing = false`, since they are also what links errors across
services; only span reporting stops.

The service on the other end must also report to ForgeOps (the Ruby SDK continues the trace
automatically from 0.12.0), and both projects must be linked in ForgeOps to see them connected.

Narrow or turn off where the header goes, for example if a third-party API rejects unknown headers:

```kotlin
ForgeOpsTracker.init { config ->
    config.propagateTraces = false // never send traceparent (default true)

    // Default null: every host. A String matches that host and its subdomains on a dot boundary
    // ("example.com" matches "api.example.com", not "badexample.com"); a Regex (or Pattern) is
    // searched for in the host, so anchor it yourself.
    config.tracePropagationTargets = listOf("internal.example", Regex("^10\\.0\\."))
}
```

## Custom metrics and infrastructure monitoring

Two explicit calls (nothing is automatic, so there is no `trackMetrics` flag): a business event you
name yourself, and a reading from one of your own hosts.

```kotlin
ForgeOpsTracker.captureMetric("signup") // value defaults to 1.0: a bare counter
ForgeOpsTracker.captureMetric("payment", 49.0) // a real magnitude; it may be negative (a refund)

ForgeOpsTracker.captureInfrastructureMetric("cpu", 0.42) // hostname defaults to serverName
ForgeOpsTracker.captureInfrastructureMetric("disk", 0.81, hostname = "db-1")
ForgeOpsTracker.flushMetrics() // optional: send right now
```

Each capture is buffered and flushed as one batch every `metricFlushInterval` /
`infrastructureMetricFlushInterval` (60 seconds by default) on a daemon scheduled executor, and once
more from a JVM shutdown hook when the program exits normally, so a short-lived cron job that captures
a few readings and returns from `main` needs nothing more; call `flushMetrics()` if it ends with
`Runtime.halt`. Every entry is stored as it was captured (a signup is a row, not a running total), so a
count or sum you compute later is exact. Both are a no-op when the client isn't enabled for the
environment.

A delivery that fails for now (a network error, a timeout, a 5xx, a 408 or a 429) keeps every entry
for the next flush, and an entry captured while a delivery is in flight is kept too (the Ruby gem's
own buffer loses it; a test pins this with a gated delivery). A batch the server rejects (any other
4xx, such as the 403 of a plan without the feature) is dropped and never sent again, and so is a batch
whose delivery has failed five flushes in a row. The buffer holds at most 1000 entries per kind and
drops further ones until a flush empties it.
A NaN or infinite value is dropped at capture: it is not valid JSON and would make the server reject
the whole batch behind it. Requires a ForgeOps plan that includes custom metrics / infrastructure
monitoring.

## Recording changes

When a feature flag flips or a remote config value changes, tell ForgeOps, and it shows the change on
the timeline next to the errors around it, so "errors started right after `new_checkout` turned on" is
one glance instead of an investigation. Call `recordChange` from your flag or config client's change
callback:

```kotlin
import io.github.lukepopwell.tracker.ForgeOpsTracker

ForgeOpsTracker.init { config ->
    config.dsn = "https://<api_key>@getforgeops.net/api/v1/events"
    config.environment = "production"
}

// Your flag client's change listener: whatever it calls with the key and the old and new values.
flagClient.addFlagChangeListener { key: String, oldValue: Boolean, newValue: Boolean ->
    ForgeOpsTracker.recordChange(
        "feature_flag",
        "$key turned ${if (newValue) "on" else "off"}",
        details = mapOf("key" to key, "from" to oldValue, "to" to newValue),
        actor = "flag-service",
    )
}
```

`kind` is one of `"feature_flag"`, `"config"`, `"migration"`, `"dependency"`, `"infrastructure"`, or
`"other"` (`Change.KINDS`); anything else is sent as `"other"`. The title is required and cut to 200
characters. Optional named arguments: `details` (a small JSON-shaped map; one holding a NaN or infinite
number is left out, since the server would reject it), `environment` (defaults to the configured one),
`service`, `actor`, `url` (http or https), `id` (an idempotency key, so recording the same change twice
keeps one), and `occurredAt` (an `Instant`, default now).

`recordChange` returns immediately: the change is queued and sent by a background coroutine, the same
bounded, single-worker shape as error delivery, never on the calling thread. It never throws, whether
the request fails or your plan doesn't include change tracking (that 403 is silent), and it's a no-op
when the client isn't enabled for the environment.

## `in_app` backtrace frames

`StackTraceElement.getFileName()` is always a bare file name (`"Foo.kt"`), never a full path
(verified directly, not assumed), so file-path matching against a configured app root isn't
possible on the JVM. Set `Configuration.appPackagePrefix` (e.g. `"com.acme.myapp"`) to mark
matching frames `in_app`; unset by default, meaning every frame reports as not-in-app until you
set it, the safe default.

## Source context

By default, each in-app backtrace frame (never a third-party dependency) is captured along with
the 5 lines of source on either side of the culprit line, read straight off disk at capture time,
so an issue's detail page can show the actual code that broke, not just a file:line:method
reference. This never applies to a frame outside your own app's code, and it fails silently (no
context, not an error) for any file that can't be read: most production deployments of a compiled
language don't ship the original `.kt` files alongside the compiled `.class`/`.jar`, so this will
typically no-op there and only produce a snippet when source is actually present on disk, such as
during local development.

This is a real, deliberate exception to "off by default is safer": literal source code is being
transmitted, not just a reference to it, and the real protection here is not this flag. Every
project on ForgeOps has its own setting (on by default, off durably and immediately once an org
owner turns it off, regardless of what any individual app's own local setting is still set to)
that governs whether the server will ever actually store what this client sends. Set
`Configuration.captureSourceContext = false` if you'd rather this client never even attempt the
disk read in the first place:

```kotlin
ForgeOpsTracker.init { config ->
    config.dsn = "..."
    config.captureSourceContext = false
}
```

## PII scrubbing

The message, backtrace, and any context you attach are scanned for likely personal data (email
addresses, formatted SSNs/credit cards, known API key/token formats, and anything under a
suspiciously-named key like `password`, `api_key`, or `ssn`) and redacted before the
payload ever leaves this process. ForgeOps itself scrubs again on arrival regardless, so this is a
second, earlier layer, not the only one. The user attached via `captureException`'s `user`
parameter or `setUser()` above is a deliberate exception: it's never scrubbed, since redacting it
would defeat the whole point of identifying users in the first place.

To disable it:

```kotlin
ForgeOpsTracker.init { config -> config.scrubPii = false }
```

## Strict privacy

For apps that can't let personal or health data (PII, PHI) leave their own servers. With strict
privacy on, an event carries only structured facts about the error, never free text that could
hold a name, an email or a diagnosis:

- The message isn't sent. `message_fingerprint` goes instead: a one-way hash of the message (after
  PII scrubbing, when that's on) with its numbers, hex ids and UUIDs taken out, so ForgeOps still
  groups errors without seeing the text.
- Breadcrumbs aren't recorded or sent, and neither are the SQL statement (even with
  `captureSqlStatement = true`) or source context.
- The user is sent as its `id` only, never `email` or `username`.
- Context and tags keep only the keys you allow, and only when the value is a string, number or
  boolean.
- Spans are named by method and host only for outbound calls (`GET api.example.com`), a span named
  by a raw path keeps only its first segment (`GET /patients/:path`, `/patients/:path`; a route
  template such as `GET /orders/{id}` is kept as it is), and span data keeps only `db.statement`
  (already masked) and `db.system`.

The exception class, stack frames, release, environment, server name, trace id and SQL object
names are sent as before. Performance samples, metrics and changes are unchanged: they're already
counts and timings.

```kotlin
ForgeOpsTracker.init { config ->
    config.strictPrivacy = true
    config.privacyAllowedFields = setOf("error_code", "feature")
}
```

Or from the environment: `FORGE_OPS_STRICT_PRIVACY=true` and
`FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature` (comma separated). An option set in code wins
over the environment. Both are off and empty by default.

ForgeOps applies the same rules again on arrival when the project's own Strict privacy setting is
on, so an app with an older or misconfigured client still can't get text stored for that project.

## Database errors

When an error comes from a database call, the event includes the names of the stored procedure, table and view its SQL touched, so the issue tells you where to start looking. This is on by default and sends identifiers only, never values. The statement is read from a `getSQL()` or `getSql()` accessor on the exception or anything it wraps (Hibernate's `JDBCException`, Spring's `BadSqlGrammarException`), reflectively, and from SQLite's `while compiling: ...` error text.

To also send the SQL statement itself, opt in. Every string and number is replaced by `?` before it
leaves your process (`WHERE email = 'a@b.co' AND id = 42` is sent as `WHERE email = ? AND id = ?`),
and ForgeOps masks it again on arrival. Escaped quotes, prefixed strings such as `X'DEADBEEF'`, and
hex, binary and exponent numbers are masked the same way:

```kotlin
ForgeOpsTracker.init { config ->
    config.captureSqlStatement = true // default false
    // config.captureSqlObjects = false // default true; false stops even the names
}
```

Each ForgeOps project also has its own "Capture the SQL behind database errors" setting. Turn it off
there and the statement is never stored for that project, whatever this flag says; the names are
still kept. A view and a table are written the same way in SQL, so both show as tables/views; the
database's own error message usually settles which it was.

## Running the tests

```bash
cd sdks/kotlin
mvn test
```

Requires a JDK the bundled Kotlin compiler (2.0.20) actually understands: JDK 21 was used to build
and test this client: **JDK 26 is confirmed broken** (`kotlin-maven-plugin` 2.0.20 fails to parse
that version string entirely, a compiler-internal error, not a version-mismatch warning). Point
`JAVA_HOME` at a JDK 17-21 install if your default is newer:

```bash
export JAVA_HOME=/usr/local/opt/openjdk@21   # or wherever your JDK 21 lives
mvn test
```
