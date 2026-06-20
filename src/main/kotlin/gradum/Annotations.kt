/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Annotations.kt  2026-06-20 20:22:43 Created by gwy
 */

package gradum

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is experimental and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalApi

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This operation can cause irreversible side effects. Review before use.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class DangerousOperation
