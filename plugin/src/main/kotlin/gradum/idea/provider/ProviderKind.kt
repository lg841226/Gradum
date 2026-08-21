/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderKind.kt  2026-08-17 15:30:00 Changed by gwy
 */
package gradum.idea.provider

import gradum.idea.utils.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Supported model providers, used to dispatch probe endpoints and to
 * resolve the localized section title through [displayKey].
 *
 * Local providers ([isCloud] == false) are always present in the
 * settings page. Cloud providers ([isCloud] == true) are managed by
 * the user through the "Add provider" tabs: a cloud kind is only probed
 * / pushed to [ProviderCoordinator] once it has been added, and removing
 * it deletes its persisted URL / API key.
 */
enum class ProviderKind(
  val displayKey: String,
  val configKey: String,
  val defaultBaseUrl: String,
  val isCloud: Boolean,
  val icon: IconKey?,
) {
  OLLAMA(
    displayKey = "gradum.settings.provider.ollama",
    configKey = "ollama",
    defaultBaseUrl = "http://localhost:11434",
    isCloud = false,
    icon = null,
  ),
  LM_STUDIO(
    displayKey = "gradum.settings.provider.lmstudio",
    configKey = "lmstudio",
    defaultBaseUrl = "http://localhost:1234",
    isCloud = false,
    icon = null,
  ),
  ZHIPU(
    displayKey = "gradum.settings.provider.zhipu",
    configKey = "zhipu",
    defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
    isCloud = true,
    icon = GradumIcons.ProviderZhipuai,
  ),
  DEEPSEEK(
    displayKey = "gradum.settings.provider.deepseek",
    configKey = "deepseek",
    defaultBaseUrl = "https://api.deepseek.com/v1",
    isCloud = true,
    icon = GradumIcons.ProviderDeepseek,
  ),
  MINIMAX(
    displayKey = "gradum.settings.provider.minimax",
    configKey = "minimax",
    defaultBaseUrl = "https://api.minimaxi.com/v1",
    isCloud = true,
    icon = GradumIcons.ProviderMinimax,
  );

  /** Wire name sent to the server's `/provider/probe` endpoint. */
  val wireName: String
    get() = configKey

  companion object {
    /** Cloud kinds offered by the "Add provider" menu. */
    val cloudKinds: List<ProviderKind> = entries.filter { it.isCloud }

    /** Local kinds always shown in the settings page. */
    val localKinds: List<ProviderKind> = entries.filter { !it.isCloud }
  }
}