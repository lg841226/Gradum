# Changelog

All notable changes to Gradum are tracked here, roughly following
[Keep a Changelog](https://keepachangelog.com/). The version number stays at
`1.0.2-experimental` while the project is still maturing; see the README for
how to download the packaged distributions.

## [1.0.2-experimental] - 2026-09-25

Command authorization and security-model rework.

**Command authorization (run_cmd)**

- `readOnlyAllowedExecutables` removed; the READ_ONLY tier no longer uses a
  whitelist. Destructive commands on paths outside the project root now pause
  and ask the user for explicit authorization (once / always / reject) instead
  of being auto-blocked.
- `CommandVerdict` gains `NeedsApproval`, classifying `rm`, recursive `chmod`,
  and `dd` against paths outside the project root. Protected system paths and
  blocked executables stay hard-blocked and cannot be authorized away.
- Session-scoped `authorizedCommandCategories` remembers per-category approvals
  so the same class of command is not re-asked within a session.

**Skills & prompts**

- `write_file` merged from `edit_file` and `save_file`; file-creation now renders
  added-line counts.
- System prompts updated so models call the tool and await user authorization
  rather than self-refusing out-of-project destructive commands.

## [1.0.1-experimental] - 2026-09-11

Packaging, distribution, and server ergonomics got their first real polish this round.

**Packaged distribution**

- The macOS app now ships with an official Gradum icon, alongside vendor and
  copyright attribution; the bundle is ad-hoc signed so Finder shows it like a
  normal app instead of a quarantine badge.
- Double-clicking the app opens a live Terminal window instead of quietly
  sitting in the background, which makes first-run behavior and logs obvious.
- The bundled JVM runtime is slimmed down with `jlink`, keeping only the JDK
  modules the server actually uses. `jdeps` figures that out automatically on
  every build. The distributed app dropped from roughly 183 MB to about 69 MB,
  or roughly 49 MB once zipped, with no behavior lost.

**Server stability**

- When the configured port is already taken, startup now prints a friendly
  hint pointing you at the fix, instead of a raw `BindException` stack trace.

**Configuration & logging**

- Settings validation errors render as a compact tree, one line per issue,
  with the offending value called out so they are easy to scan even in narrow
  terminal windows.
- Error-level log lines now use bright text on a red background, matching the
  rest of the raised-surface color scheme.
