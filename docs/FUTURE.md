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
