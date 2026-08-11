# Moataz AI — Integration identifiers

This document records public identifiers changed during the RikkaHub Agent → Moataz AI rebrand. Internal Kotlin/Android namespaces remain intentionally unchanged where possible.

## Android identity

- applicationId: `ai.moataz`
- namespace: `me.rerere.rikkahub` (unchanged intentionally)
- launcher name: `Moataz AI`

## Deep links

Current scheme: `moatazai://`

- `moatazai://shortcut`
- `moatazai://codex/oauth`
- `moatazai://gemini/oauth`
- `moatazai://mcp-oauth-callback`

Codex and Gemini OAuth managers use local-loopback callbacks during the provider OAuth flow and then route the result back into the app. The app-facing scheme change is internal to this fork.

MCP OAuth is externally significant because `MCP_OAUTH_REDIRECT_URI` is sent to OAuth-capable MCP servers/authorization servers. Existing registrations created with `rikkahub://mcp-oauth-callback` need reauthorization or re-registration. Providers that require a statically allow-listed redirect URI must add `moatazai://mcp-oauth-callback` outside this repository.

## External automation actions

- `ai.moataz.RUN_TASK`
- `ai.moataz.RUN_CHAT`
- `ai.moataz.workflow.GEOFENCE_TRANSITION`

Tasker, MacroDroid, ADB scripts, or other integrations using the old RikkaHub Agent action strings must be updated.

## OAuth configuration that may require external work

- MCP authorization servers with fixed redirect allow-lists: add `moatazai://mcp-oauth-callback`.
- Any third-party automation that hardcodes the old scheme/action values: update it.
- Codex/Gemini provider-side loopback OAuth behavior remains implemented by the existing managers; no app package refactor was performed.
