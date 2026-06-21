/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Annotations.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This operation can cause irreversible side effects. Review before use.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class DangerousOperation
