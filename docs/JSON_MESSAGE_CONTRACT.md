# Structured JSON messages

`messagejson`, `jsonmessage`, `jmsg` and `jm` map to `message-json` in both
direct binding and imported skills. Arguments use native priority
`message`, `msg`, `m`; repeated keys use the last value. A missing message
rejects binding. The pinned contract is public MythicMobs 5.7.2 on Paper 1.21.1.

The action resolves the message separately for every selected player, retaining
the caller's caster and trigger. It honors the execution audience and ignores
non-player or location recipients. The Bungee component serializer used by the
native mechanic preserves component arrays, text styles, hover events and
click events. A failed parse sends nothing for that recipient and increments
`JsonMessageActions#message-json` in the existing skill fault diagnostics.

Matching outer double quotes and then single quotes are removed. Native
message special characters (`<&dq>`, `<&co>` and the other documented tokens),
legacy role substitutions and ampersand color codes are prepared before runtime
placeholder resolution. JSON backslash escapes remain intact. Like the native
mechanic, placeholder values are inserted before JSON parsing; values that
produce an invalid component do not become a plain-text fallback.

```yaml
Skills:
  - 'messagejson{m=''[{"text":"Hello <target.name>","color":"gold","clickEvent":{"action":"suggest_command","value":"/help"}}]''} @trigger'
```

The importer preserves the raw component body, selector and execution options.
Tests cover aliases, argument priority, quote/escape handling, recipient context,
audience exclusion, invalid per-recipient payloads and imported scheduling.
The 2026-11-26 Maven verification passes 2,865 tests and 11 SpotBugs checks.
Real Paper/client comparison and final artifact release validation remain
pending. This is an implemented mechanism, not a completed acceptance claim;
external PlaceholderAPI expansion and all client component variants are outside
the verified scope.
