/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Annotations.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum

@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This operation can cause irreversible side effects. Review before use.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class DangerousOperation
