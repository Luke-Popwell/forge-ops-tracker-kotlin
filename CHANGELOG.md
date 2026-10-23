# Changelog

## 0.17.0 (2026-10-23)

- PII scrubbing catches card numbers however they're grouped (bare, 4-4-4-4, Amex's 4-6-5, or next to a
  security code) when the digits have a card network's prefix and pass the Luhn check, so long ids stay
  readable; IBANs, compact or in groups of four, by their own check digits; and fields named `iban`,
  `account_number`, `routing_number` or `bank_account`. The rules are in sdks/PII_SCRUBBING.md, the same in
  every SDK.

## 0.16.0 (2026-10-22)

- Strict privacy: the message fingerprint now also replaces email addresses with `<email>` and quoted
  values (`"..."`, and `'...'` when the quotes aren't part of a word) with `<str>`, before the existing
  steps. `KeyError: 'Jane Doe'` and `KeyError: 'Bob Smith'` now group together, and no guessable value sits
  behind the hash. Errors whose messages contain quotes or emails get new fingerprints once, so they open
  new issues after upgrading.

## 0.15.1 (2026-10-22)

- The README says what strict privacy does in its opening lines.
- The source is public at https://github.com/Luke-Popwell/forge-ops-tracker-kotlin, and the package links to it.

## 0.15.0 (2026-10-20)

- Strict privacy: a raw path now keeps only its first segment, instead of only losing its digit
  segments, since a later segment can name a person even without a digit (`/patients/janedoe`).
  `GET /patients/jane-doe/12` is sent as `GET /patients/:path`, `/42/edit` as `/:id/:path`. A route
  template (any segment such as `:id`, `{id}`, `<int:id>`, `[slug]` or `*path`) is kept as it is, so
  `GET /v1/orders/{id}` is no longer sent as `GET /:id/orders/{id}`. Applies to span names and
  performance sample transaction names, the same rule the server applies. New
  `StrictPrivacy.strictPath`; `pathWithoutIds` still works (deprecated) and now calls it.

## 0.14.0 (2026-10-20)

- Strict privacy, for apps that can't let personal or health data leave their own servers. New
  `Configuration.strictPrivacy` (or `FORGE_OPS_STRICT_PRIVACY=true`) sends only structured facts:
  the message is replaced by `message_fingerprint`, a hash of it (after PII scrubbing) with numbers
  and ids taken out, so errors still group; breadcrumbs aren't recorded or sent; the SQL statement and source context
  aren't sent; the user is sent as its `id` only; and context and tags keep only the keys in
  `privacyAllowedFields` (or `FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature`) with a string,
  number or boolean value. Spans keep only method and host for outbound calls, a span named by a
  raw path has its ids taken out (`GET /patients/:id`, `/patients/:id`), and span data keeps only `db.statement` and
  `db.system`. Off by default; nothing changes unless it's turned on. ForgeOps applies the same
  rules on arrival when the project's own Strict privacy setting is on.

## 0.13.0 (2026-10-13)

- A batch the server will never accept is no longer re-sent forever. Performance samples and custom
  and infrastructure metrics used to keep a batch after any failed delivery and send it again, with
  more added, at every flush. A 4xx answer other than 408 and 429 (a wrong key, a plan without the
  feature, a batch the server can't read or that is too large) now drops the batch at once, since
  the same batch would only get the same answer. Before this, a rejected metric batch also stayed in
  the 1000-entry buffer, so once the buffer filled, every metric captured afterward was dropped too.
- A delivery that fails for now (a network error, a timeout, a 5xx, a 408 or a 429) still keeps the
  batch for the next flush, but only so long: after 5 failed flushes in a row the batch is dropped
  and the next one starts clean. A successful flush starts the count over. The 1000-entry cap on
  each metric buffer is unchanged.
- Each drop is reported once through `config.logger`, for example `[ForgeOpsTracker] dropped 12
  metrics: the server rejected it` or `... delivery failed 5 times in a row`, and a non-2xx answer
  is logged with its status (`delivery rejected: HTTP 403`, `delivery failed: HTTP 503`). Nothing
  about delivery throws.
- No API changes: `Client`'s `deliver...` functions still return `true` only for a 2xx, and errors,
  traces and changes are still sent once and never retried.

## 0.12.0 (2026-10-02)

- **The default environment changed from `development` to `production`.** With
  `FORGE_OPS_ENVIRONMENT` unset and no `config.environment` assignment, the client used to report
  itself as `development`, which isn't one of the default enabled environments (`production`,
  `staging`), so an app set up with only a DSN silently sent nothing. It now defaults to
  `production`, so a DSN alone is enough. To keep the old behavior, set
  `FORGE_OPS_ENVIRONMENT=development` (or `config.environment = "development"`) wherever you don't
  want errors sent, such as developer machines and CI. `SPRING_PROFILES_ACTIVE` is not read: it
  lists profiles, not an environment name.
- When a DSN is configured but the environment isn't enabled, `init` now says so once per process,
  through `config.logger` if one is set and on `stderr` otherwise: `[ForgeOps] Not sending: this
  environment is "development", and only production, staging are enabled. Set
  FORGE_OPS_ENVIRONMENT=production (or add "development" to the enabled environments) to send from
  here.` Nothing is printed when no DSN is configured. New `Configuration.notSendingWarning` returns
  that message, or null.
- The README's catch-site and `flush()` examples now include their imports.

## 0.11.2 (2026-10-01)

- Changes recorded just before a program exits are no longer lost. A deploy script or cron job that
  called `recordChange` and then returned from `main` or called `exitProcess` could end before the
  background coroutine sent the change. The first recorded change now registers a JVM shutdown hook
  too, the same as errors got in 0.11.1, and a program exiting with both errors and changes queued
  still waits at most 5 seconds in all. `ForgeOpsTracker.flush(timeout)` now sends queued changes
  as well as errors, within the one timeout.

## 0.11.1 (2026-10-01)

- Errors reported just before a program exits are no longer lost. A command-line tool or cron job
  that reported an error and then returned from `main` or called `exitProcess` could end before the
  background coroutine sent it, and so could a crash reported by the uncaught-exception handler. The
  first report now registers a JVM shutdown hook that sends whatever is still queued before the JVM
  exits, waiting at most 5 seconds; a program that never reports anything doesn't wait at all. New
  `ForgeOpsTracker.flush(timeout)` (5 seconds by default) sends queued errors right away, for a
  program that ends with `Runtime.halt`, which skips shutdown hooks.

## 0.11.0 (2026-09-29)

- SQL masking now matches the server's newer rules, so fewer values can slip through. A
  backslash-escaped quote no longer ends a string early (`'o\'brien'` and `E'o\'brien'` are each one
  `?`), a string's type prefix goes with it (`X'DEADBEEF'`, `N'...'`, `B'...'`, `U&'...'`), and hex,
  binary, exponent and leading-dot numbers are masked (`0x1F`, `0b101`, `3e10`, `1.5E-3`, `.5`). A
  `database` span whose `dbSystem` is `mysql` or `mariadb` also has its double-quoted strings
  masked, since those databases read `"..."` as a string, not a name. A string cut off right after a
  backslash is masked to the end too. Applies to database spans and to the statement sent with a
  database error.

## 0.10.0 (2026-09-25)

- A `database` span can now carry the SQL it ran: new `span` and `recordSpan` overloads take
  `statement` (and optionally `dbSystem`, such as `"postgresql"` or `"sqlite"`). The statement is
  masked before it leaves the process (every string and number literal becomes `?`), cut at 4000
  characters, and sent in the span's data as `db.statement`, with `db.system` lowercased. A
  `db.statement` put in `data` directly is masked the same way. Both are ignored on spans of any
  other kind. The existing overloads are unchanged, so code compiled against an earlier version
  keeps working.

## 0.9.0 (2026-09-25)

- New `ForgeOpsTracker.recordChange(kind, title, details, environment, service, actor, url, id, occurredAt)` tells ForgeOps what changed in your program, typically from a feature flag or remote config change callback, so the change shows on the timeline next to the errors around it. `kind` is one of `Change.KINDS` (anything else is sent as `"other"`). Queued and sent to `/api/v1/changes` by a background coroutine; it never throws, a failed request or a plan without change tracking is silent, and it's a no-op when the client isn't enabled. New `Configuration.changesUri` and `Client.deliverChange`.

## 0.8.0

- Distributed tracing across services, using the W3C Trace Context standard (`traceparent`). `ForgeOpsTracker.httpSpan(method, url) { headers -> ... }` records an outgoing HTTP call as an `http` span named after the method and host, and hands the block the `traceparent` header to add to that request; its parent id is the span's own id, so the called service's root span nests under it. `ForgeOpsTracker.trace(rootName, traceparent) { ... }` continues an incoming request's trace (same trace id, root span parented under the caller's span); a missing or malformed value starts a fresh trace. New `propagateTraces` (default true) and `tracePropagationTargets` (default null, meaning every host; or a list of host Strings, each matching that host and its subdomains, and/or Regexes) control where the header goes.
- Errors captured inside a `trace` now carry a top-level `trace_id`, so ForgeOps can link them to errors from other services that handled the same request. `ForgeOpsTracker.currentTraceId()` returns it. Errors captured outside a trace are unchanged.
- With `trackTracing = false`, a `trace` block still has a trace id (attached to errors and propagated in the header, since that is also what links errors across services); only span reporting stops. Previously nothing happened inside a `trace` with tracing off.
- Trace and span ids were already W3C shaped (32 and 16 lowercase hex characters); they are now also guaranteed never to be all zeros, the one value the standard reserves as invalid.

This client has earlier tagged releases (see `pom.xml`'s version history in source control), but no `CHANGELOG.md` existed for it before this entry; it starts here rather than backfilling every earlier version.
