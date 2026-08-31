/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Annotations.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum

@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This operation can cause irreversible side effects. Review before use.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class DangerousOperation
