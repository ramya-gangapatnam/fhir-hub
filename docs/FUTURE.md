# FUTURE.md — Deferred Decisions

Items deliberately deferred from the demo scope. Each entry exists to prevent the idea from being lost without letting it sneak into the current work.

## Tooling

- **Decide on markdown formatting convention.** Current state mixes Spec Kit's `*italic*` style with the editor formatter's `_italic_` style. Each save of a Spec Kit-generated markdown file produces incidental italic-marker churn in the diff. Pick one (either configure Prettier/markdownlint to use `*`, or accept `_` and reformat existing files once) when the churn becomes noticeable in code review. Logged 2026-05-20 alongside the SC-007 tweak.

## logback-spring.xml date pattern uses invalid 'T' literal

The JSON_PATTERN timestamp format in backend/src/main/resources/logback-spring.xml
uses `yyyy-MM-dd''T''HH:mm:ss.SSSXXX`. Java's SimpleDateFormat rejects `T` as a
pattern letter ("Unknown pattern letter: T"); Logback logs a StatusManager warning
and falls back to ISO8601, so log output is still valid — but the configured format
is not what's actually used. Fix: escape the literal as `'T'` correctly for
Logback's date converter, or accept the ISO8601 fallback and simplify the pattern.
Surfaced during the Batch 3A tests-first run (LogbackSpringXmlPipelineTest).
Low priority — no functional impact, output is still valid JSON.

## Migrate REST-assured contract tests to WebTestClient (REST-assured 5.5.0 + JDK 21 + Spring Boot 4 GET NPE)

REST-assured 5.5.0 throws a `NullPointerException` deep inside its Groovy/HTTPBuilder
machinery on every HTTP GET request when running on JDK 21 + Spring Boot 4. The
exception is raised on the **client side** before the request reaches the server —
the server logs show no inbound request at all.

Stack-trace summary (top 9 frames):
```
java.lang.NullPointerException
  at java.lang.Class.isAssignableFrom(Native Method)
  at org.codehaus.groovy.runtime.metaclass.ClosureMetaClass.invokeOnDelegationObject(ClosureMetaClass.java:367)
  at org.codehaus.groovy.runtime.metaclass.ClosureMetaClass.invokeOnDelegationObjects(ClosureMetaClass.java:335)
  at org.codehaus.groovy.runtime.metaclass.ClosureMetaClass.invokeMethod(ClosureMetaClass.java:324)
  at io.restassured.internal.RequestSpecificationImpl$_sendHttpRequest_closure27.doCall(RequestSpecificationImpl.groovy:1484)
  at io.restassured.internal.http.HTTPBuilder.doRequest(HTTPBuilder.java:494)
  at io.restassured.internal.http.HTTPBuilder.request(HTTPBuilder.java:453)
  at io.restassured.internal.RequestSpecificationImpl.sendHttpRequest(RequestSpecificationImpl.groovy:1480)
  at io.restassured.internal.RequestSpecificationImpl.get(RequestSpecificationImpl.groovy:172)
```

Root cause: REST-assured's bundled (fork of) `HTTPBuilder` uses Groovy closure
delegation that no longer resolves cleanly under JDK 21 — `Class.isAssignableFrom`
is invoked with a null target type. POST requests dispatch through a different
path and are unaffected; only GETs trigger it. Multiple attempted workarounds at
the parser / encoder / `defaultParser` layers all failed because the NPE is
upstream of any response-parsing logic.

**Workaround in this branch:** `@Disabled` on the seven GET-using tests with this
comment quoted so the disabling can be searched later:
- `FhirPatientReadContractTest` (3 tests)
- `FhirEncounterReadContractTest` (3 tests)
- `AuditEmissionIntegrationTest` (1 test — also depends on US2 Inspector)

**Planned fix:** migrate the affected tests off REST-assured onto Spring's
`WebTestClient` (or `TestRestTemplate`), which uses native HTTP clients on the
JDK 21 / Spring Boot 4 stack and does not depend on Groovy.

The production controllers (`FhirReadController`, the Inspector reads in US2)
are unaffected — only test infrastructure needs to change.
