# Gradum Versioning Rules

This document defines how Gradum versions its releases. Following it keeps the
repository version, the packaged server, and the plugin from drifting apart.

---

## 1. Version Format

`MAJOR.MINOR.PATCH[-pre-release]`, per [Semantic Versioning](https://semver.org).

The current pre-release is `experimental` (see §4).

---

## 2. When to Bump

| Level   | Trigger                                         | Example                                      |
|---------|-------------------------------------------------|----------------------------------------------|
| PATCH   | Bug fix, perf work, behavior-neutral refactor   | Fix a crash, change log formatting           |
| MINOR   | Backward-compatible new feature                 | Add a skill, endpoint, or event type         |
| MAJOR   | Breaking change to the HTTP API contract        | Change NDJSON event fields; old plugin stops |

### What does NOT bump the version

- Docs, README, comments
- CI / build config (when the artifact behavior is unchanged)
- Tests and scaffolding

These changes do not ship a standalone release. They ride along with the next
versioned release.

---

## 3. Who Bumps It

- **Server version** lives in `src/main/java/gradum/Version.java`
  (`GRADUM_VERSION`) and in `build.gradle.kts` (`version =`). They stay in sync.
- **Plugin version** is independent. It lives in
  `plugin/build.gradle.kts` (`project.version` of the plugin module) and no
  longer inherits the server version. Plugin build numbers are bumped per
  IDE-side change.
- **Packaged app version** (jpackage) strips the pre-release suffix: the server
  `1.0.0-experimental` becomes the macOS app version `1.0.0`. This is required
  because Apple rejects version strings like `1.0.0-experimental`.

---

## 4. Pre-release Suffix

Use the suffix whenever the release is not stable.

| Suffix          | Meaning                                             |
|-----------------|-----------------------------------------------------|
| `-experimental` | Early, APIs may still change; for community testing |
| `-beta`         | Feature-complete, stabilizing                       |
| `-rc`           | Release candidate, no breaking changes planned      |
| *(none)*        | Stable release                                      |

**Rules:**

- Write suffixes in full (`-experimental`, not `-exp`).
- A pre-release MUST NOT be published without a suffix.
- Only drop the suffix when we commit to API stability.

---

## 5. Tagging & Releases

- Tag every release: `vMAJOR.MINOR.PATCH` for stable, `vMAJOR.MINOR.PATCH-<suffix>`
  for pre-releases. Example: `v1.0.0-experimental`.
- Update `Version.java` first, run the build, then tag and publish.