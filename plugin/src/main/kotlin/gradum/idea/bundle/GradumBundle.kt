/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumBundle.kt  2026-07-17 09:35:23 Changed by gwy
 */

package gradum.idea.bundle

import com.intellij.DynamicBundle
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.annotations.PropertyKey
import java.util.*

/**
 * Resource-bundle accessor for all user-visible Gradum strings.
 *
 * The previous implementation trusted a hard-coded `messages.GradumBundle`
 * path and would throw [MissingResourceException] with no warning when a
 * key was missing — leaving the UI to render an empty label and making
 * "did I spell the key wrong?" bugs invisible. Two small hardening changes
 * are in place:
 *
 * 1. **Startup probe.** An `init {}` block tries to resolve one well-known
 *    key on object-init. If the resource is missing entirely (renamed folder,
 *    wrong base name, packaged plugin with stripped .properties), the IDE
 *    log gets a single clear warning with the exact path that failed.
 * 2. **Per-key fallback.** [message] catches [MissingResourceException] and
 *    returns the key wrapped in `???…???` markers. The UI is never blank,
 *    a missing key is obvious in screenshots, and we get a log line that
 *    points at the offending key + the bundle's base name.
 *
 * The hard-coded [BUNDLE_NAME] is the JetBrains-recommended pattern (the
 * class lives in `gradum.idea.bundle` but its resources sit in the
 * `messages` package on purpose, so a class-based path would require
 * moving 132 keys — out of scope for this refactor). The probe and
 * fallback are the right defenses for the "I moved the folder" foot-gun
 * without re-architecting the resource layout.
 */
object GradumBundle : DynamicBundle(BUNDLE_NAME) {

  private val log: Logger = Logger.getInstance(GradumBundle::class.java)

  /**
   * Returns the localized message for the given key, or a visible fallback
   * marker if the key is not present in the active locale's bundle.
   *
   * Substituted parameters use the standard Java `MessageFormat` syntax
   * (`{0}`, `{1}`, …). The fallback marker is `???<key>???` so a missing
   * key is obvious in the UI without breaking layout.
   *
   * @param key the resource key (IntelliJ's @PropertyKey checks the key
   *   against [BUNDLE_NAME] at compile time).
   * @param params format arguments to substitute into the localized
   *   message template, if any.
   */
  @JvmStatic
  fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any): String {
    return try {
      getMessage(key, *params)
    } catch (exception: MissingResourceException) {
      log.warn("Missing i18n key '$key' in bundle '$BUNDLE_NAME'", exception)
      "???$key???"
    }
  }

  init {
    // Probe one well-known key at object-init so a renamed/missing
    // resource surfaces as a single clear log line at plugin startup
    // instead of dozens of warnings later from the UI tree.
    try {
      getMessage(PROBE_KEY)
    } catch (exception: MissingResourceException) {
      log.warn(
        "Gradum resource bundle '$BUNDLE_NAME' is unreachable. " +
          "Check that src/main/resources/messages/GradumBundle*.properties is packaged.",
        exception,
      )
    }
  }
}

private const val BUNDLE_NAME: String = "messages.GradumBundle"
private const val PROBE_KEY: String = "gradum.toolwindow.welcome"
