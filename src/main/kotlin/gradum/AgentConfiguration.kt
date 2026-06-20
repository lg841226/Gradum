package gradum

data class AgentConfiguration(
    val baseUrl: String = "http://localhost:11434",
    val modelName: String = "minimax-m2.5:cloud",
    val timeoutSeconds: Int = 300,
    val enableThinking: Boolean = false,
    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
    val contextWindowSize: Int = 4096,
    val maxTokensToGenerate: Int = 2048,
    val providerName: String = "ollama",
)
