package gradum.idea.utils

import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

object GradumIcons {
  // Entries whose drawing is an exact copy of a platform icon point at the
  // matching AllIconsKeys entry instead of shipping the same SVG twice; the
  // platform also resolves the light/dark variant for us. Everything else is a
  // genuinely custom icon living under /icons.
  val Send = PathIconKey("/icons/send/send.svg", GradumIcons::class.java)
  val Edit: IconKey = AllIconsKeys.Actions.Edit
  val Like = PathIconKey("/icons/like/like.svg", GradumIcons::class.java)
  val LikeSelected = PathIconKey("/icons/like-selected/like-selected.svg", GradumIcons::class.java)
  val Dislike = PathIconKey("/icons/dislike/dislike.svg", GradumIcons::class.java)
  val DislikeSelected = PathIconKey("/icons/dislike-selected/dislike-selected.svg", GradumIcons::class.java)
  val Search: IconKey = AllIconsKeys.Actions.Search
  val Table = PathIconKey("/icons/table/table.svg", GradumIcons::class.java)
  val Warning: IconKey = AllIconsKeys.General.Warning
  val Cloud = PathIconKey("/icons/cloud/cloud.svg", GradumIcons::class.java)
  val FeatChat = PathIconKey("/icons/feat/chat/chat.svg", GradumIcons::class.java)
  val FeatCode = PathIconKey("/icons/feat/code/code.svg", GradumIcons::class.java)
  val FeatQuestion = PathIconKey("/icons/feat/question/question.svg", GradumIcons::class.java)
  val FeatText = PathIconKey("/icons/feat/text/text.svg", GradumIcons::class.java)
  val Ran = PathIconKey("/icons/cmd/cmd.svg", GradumIcons::class.java)
  val Explore: IconKey = AllIconsKeys.Actions.ProjectWideAnalysisOff
  val Build: IconKey = AllIconsKeys.Actions.Compile
  val ScrollDown: IconKey = AllIconsKeys.RunConfigurations.Scroll_down
  val ScrollUp: IconKey = AllIconsKeys.RunConfigurations.Scroll_up
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
  val BulletList = PathIconKey("/icons/bullet-list/bullet-list.svg", GradumIcons::class.java)
  val Chat = PathIconKey("/icons/chat-mode/chat-mode.svg", GradumIcons::class.java)
  val Web: IconKey = AllIconsKeys.Nodes.PpWeb
  val Github = PathIconKey("/icons/github/github.svg", GradumIcons::class.java)
  val Mcp = PathIconKey("/icons/mcp/mcp.svg", GradumIcons::class.java)
  val BadgeVerified = PathIconKey("/icons/badge/verified.svg", GradumIcons::class.java)

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
    return PROVIDER_KEYWORD_MAP.entries.firstOrNull {
      lower.contains(other = it.key)
    }?.value
  }
}
