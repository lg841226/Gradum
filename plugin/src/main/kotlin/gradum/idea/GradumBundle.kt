/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumBundle.kt  2026-06-24 15:18:15 Changed by gwy
 */

package gradum.idea

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE_NAME = "messages.GradumBundle"

object GradumBundle : DynamicBundle(BUNDLE_NAME) {
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any) =
        getMessage(key, *params)
}