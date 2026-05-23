# FUTURE.md — Deferred Decisions

Items deliberately deferred from the demo scope. Each entry exists to prevent the idea from being lost without letting it sneak into the current work.

## Tooling

- **Decide on markdown formatting convention.** Current state mixes Spec Kit's `*italic*` style with the editor formatter's `_italic_` style. Each save of a Spec Kit-generated markdown file produces incidental italic-marker churn in the diff. Pick one (either configure Prettier/markdownlint to use `*`, or accept `_` and reformat existing files once) when the churn becomes noticeable in code review. Logged 2026-05-20 alongside the SC-007 tweak.
