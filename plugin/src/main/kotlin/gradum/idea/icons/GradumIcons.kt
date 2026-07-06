/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumIcons.kt  2026-07-05 18:02:23 Changed by gwy
 */

package gradum.idea.icons

import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey

object GradumIcons {
  val Send = PathIconKey("/icons/send/send.svg", GradumIcons::class.java)
  val Edit = PathIconKey("/icons/edit/edit.svg", GradumIcons::class.java)
  val Like = PathIconKey("/icons/like/like.svg", GradumIcons::class.java)
  val LikeSelected = PathIconKey("/icons/like-selected/like-selected.svg", GradumIcons::class.java)
  val Search = PathIconKey("/icons/search/search.svg", GradumIcons::class.java)
  val Markdown = PathIconKey("/icons/markdown/markdown.svg", GradumIcons::class.java)
  val Kotlin = PathIconKey("/icons/file-type/kotlin/kotlin.svg", GradumIcons::class.java)
  val Python = PathIconKey("/icons/file-type/python/python.svg", GradumIcons::class.java)
  val TypeScript = PathIconKey("/icons/file-type/typescript/typescript.svg", GradumIcons::class.java)
  val Jsx = PathIconKey("/icons/file-type/jsx/jsx.svg", GradumIcons::class.java)
  val Tsx = PathIconKey("/icons/file-type/tsx/tsx.svg", GradumIcons::class.java)
  val Png = PathIconKey("/icons/file-type/image/image.svg", GradumIcons::class.java)
  val Image = PathIconKey("/icons/image/image.svg", GradumIcons::class.java)
  val Warning = PathIconKey("/icons/warning/warning.svg", GradumIcons::class.java)
  val Auto = PathIconKey("/icons/auto/auto.svg", GradumIcons::class.java)
  val Cloud = PathIconKey("/icons/cloud/cloud.svg", GradumIcons::class.java)
  val Hands = PathIconKey("/icons/hands.png", GradumIcons::class.java)
  val FeatChat = PathIconKey("/icons/feat/chat/chat.svg", GradumIcons::class.java)
  val FeatCode = PathIconKey("/icons/feat/code/code.svg", GradumIcons::class.java)
  val FeatQuestion = PathIconKey("/icons/feat/question/question.svg", GradumIcons::class.java)
  val FeatText = PathIconKey("/icons/feat/text/text.svg", GradumIcons::class.java)
  val Ran = PathIconKey("/icons/cmd/cmd.svg", GradumIcons::class.java)
  val Explore = PathIconKey("/icons/explore/explore.svg", GradumIcons::class.java)
  val Build = PathIconKey("/icons/build/build.svg", GradumIcons::class.java)
  val Save = PathIconKey("/icons/save/save.svg", GradumIcons::class.java)
  val ModelTools = PathIconKey("/icons/tools/tool.svg", GradumIcons::class.java)
  val ModelVision = PathIconKey("/icons/vison/vison.svg", GradumIcons::class.java)

  val ProviderAlibaba = PathIconKey("/icons/model-provider/alibaba.svg", GradumIcons::class.java)
  val ProviderAnthropic = PathIconKey("/icons/model-provider/anthropic.svg", GradumIcons::class.java)
  val ProviderDeepseek = PathIconKey("/icons/model-provider/deepseek.svg", GradumIcons::class.java)
  val ProviderGoogle = PathIconKey("/icons/model-provider/google.svg", GradumIcons::class.java)
  val ProviderMeta = PathIconKey("/icons/model-provider/meta.svg", GradumIcons::class.java)
  val ProviderMinimax = PathIconKey("/icons/model-provider/minimax.svg", GradumIcons::class.java)
  val ProviderMistral = PathIconKey("/icons/model-provider/mistral.svg", GradumIcons::class.java)
  val ProviderOpenai = PathIconKey("/icons/model-provider/openai.svg", GradumIcons::class.java)
  val ProviderXai = PathIconKey("/icons/model-provider/xai.svg", GradumIcons::class.java)
  val ProviderXiaomi = PathIconKey("/icons/model-provider/xiaomi.svg", GradumIcons::class.java)
  val ProviderZhipuai = PathIconKey("/icons/model-provider/zhipuai.svg", GradumIcons::class.java)

  val ColorLogo = PathIconKey("/icons/logo/color_logo.svg", GradumIcons::class.java)

  private val PROVIDER_KEYWORD_MAP = mapOf(
    "qwen" to ProviderAlibaba,
    "claude" to ProviderAnthropic,
    "deepseek" to ProviderDeepseek,
    "gemini" to ProviderGoogle,
    "gemma" to ProviderGoogle,
    "llama" to ProviderMeta,
    "minimax" to ProviderMinimax,
    "mistral" to ProviderMistral,
    "mixtral" to ProviderMistral,
    "gpt" to ProviderOpenai,
    "o1" to ProviderOpenai,
    "o3" to ProviderOpenai,
    "o4" to ProviderOpenai,
    "grok" to ProviderXai,
    "mimo" to ProviderXiaomi,
    "glm" to ProviderZhipuai
  )

  fun resolveModelIcon(modelName: String): IconKey? {
    val lower = modelName.lowercase()
    return PROVIDER_KEYWORD_MAP.entries.firstOrNull { lower.contains(it.key) }?.value
  }
}
