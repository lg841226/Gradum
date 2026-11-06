const MODEL_DISPLAY_NAMES = new Map(Object.entries({
  "qwen": "Qwen",
  "qwen2": "Qwen 2",
  "qwen2.5": "Qwen 2.5",
  "qwen2.5-coder": "Qwen 2.5 Coder",
  "qwen2.5vl": "Qwen 2.5 VL",
  "qwen2-vl": "Qwen 2 VL",
  "qwen2.5-vl": "Qwen 2.5 VL",
  "qwen3": "Qwen 3",
  "qwen3.5": "Qwen 3.5",
  "qwen3.6": "Qwen 3.6",
  "qwen3-coder": "Qwen 3 Coder",
  "qwen3-coder-next": "Qwen 3 Coder Next",
  "qwen3-next": "Qwen 3 Next",
  "qwen3-vl": "Qwen 3 VL",
  "qwen3-max": "Qwen 3 Max",
  "qwen-long": "Qwen Long",
  "qwq": "QwQ",
  "qvq": "QVQ",
  "llama": "Llama",
  "llama2": "Llama 2",
  "llama3": "Llama 3",
  "llama3.1": "Llama 3.1",
  "llama3.2": "Llama 3.2",
  "llama3.3": "Llama 3.3",
  "llama4": "Llama 4",
  "codellama": "CodeLlama",
  "deepseek": "DeepSeek",
  "deepseek-r1": "DeepSeek R1",
  "deepseek-r1-lite": "DeepSeek R1 Lite",
  "deepseek-r1-distill": "DeepSeek R1 Distill",
  "deepseek-v2": "DeepSeek V2",
  "deepseek-v2.5": "DeepSeek V2.5",
  "deepseek-v2-lite": "DeepSeek V2 Lite",
  "deepseek-v3": "DeepSeek V3",
  "deepseek-v3.1": "DeepSeek V3.1",
  "deepseek-v3.2": "DeepSeek V3.2",
  "deepseek-coder": "DeepSeek Coder",
  "deepseek-coder-v2": "DeepSeek Coder V2",
  "deepseek-coder-v2.5": "DeepSeek Coder V2.5",
  "minimax-m2": "MiniMax M2",
  "minimax-m2.1": "MiniMax M2.1",
  "minimax-m2.5": "MiniMax M2.5",
  "minimax-m2.7": "MiniMax M2.7",
  "minimax-m3": "MiniMax M3",
  "gemma": "Gemma",
  "gemma2": "Gemma 2",
  "gemma3": "Gemma 3",
  "gemma4": "Gemma 4",
  "codegemma": "CodeGemma",
  "gemini-1.5-pro": "Gemini 1.5 Pro",
  "gemini-1.5-flash": "Gemini 1.5 Flash",
  "gemini-1.5-flash-8b": "Gemini 1.5 Flash 8B",
  "gemini-2.0-flash": "Gemini 2.0 Flash",
  "gemini-2.0-flash-lite": "Gemini 2.0 Flash Lite",
  "gemini-2.0-pro": "Gemini 2.0 Pro",
  "gemini-2.5-flash": "Gemini 2.5 Flash",
  "gemini-2.5-pro": "Gemini 2.5 Pro",
  "gemini-2.5-flash-lite": "Gemini 2.5 Flash Lite",
  "gemini-3-pro": "Gemini 3 Pro",
  "glm-4": "GLM 4",
  "glm-4.5": "GLM 4.5",
  "glm-4.6": "GLM 4.6",
  "glm-4.7": "GLM 4.7",
  "glm-4.7-flash": "GLM 4.7 Flash",
  "glm-5": "GLM 5",
  "glm-5.1": "GLM 5.1",
  "glm-5.2": "GLM 5.2",
  "glm-z1": "GLM Z1",
  "glm-z1-rumination": "GLM Z1 Rumination",
  "chatglm": "ChatGLM",
  "chatglm2": "ChatGLM 2",
  "chatglm3": "ChatGLM 3",
  "chatglm4": "ChatGLM 4",
  "kimi-k2": "Kimi K2",
  "kimi-k2.5": "Kimi K2.5",
  "kimi-k2.6": "Kimi K2.6",
  "kimi-k2.7": "Kimi K2.7",
  "kimi-k2.7-thinking": "Kimi K2.7 Thinking",
  "kimi-k3": "Kimi K3",
  "kimi-k3-mini": "Kimi K3 Mini",
  "kimi-k3-thinking": "Kimi K3 Thinking",
  "moonshot-v1": "Moonshot V1",
  "moonshot-v2": "Moonshot V2",
  "moonshot-v2-thinking": "Moonshot V2 Thinking",
  "kimi": "Kimi",
  "mistral": "Mistral",
  "mistral-7b": "Mistral 7B",
  "mistral-nemo": "Mistral Nemo",
  "mistral-small": "Mistral Small",
  "mistral-medium": "Mistral Medium",
  "mistral-large": "Mistral Large",
  "mistral-large-2": "Mistral Large 2",
  "mistral-large-latest": "Mistral Large",
  "mistral-small-latest": "Mistral Small",
  "mistral-tiny": "Mistral Tiny",
  "mixtral": "Mixtral",
  "mixtral-8x7b": "Mixtral 8x7B",
  "mixtral-8x22b": "Mixtral 8x22B",
  "codestral": "Codestral",
  "codestral-mamba": "Codestral Mamba",
  "codestral-22b": "Codestral 22B",
  "pixtral": "Pixtral",
  "pixtral-large": "Pixtral Large",
  "ministral": "Ministral",
  "ministral-3b": "Ministral 3B",
  "ministral-8b": "Ministral 8B",
  "phi": "Phi",
  "phi-2": "Phi 2",
  "phi-3": "Phi 3",
  "phi-3-mini": "Phi 3 Mini",
  "phi-3-small": "Phi 3 Small",
  "phi-3-medium": "Phi 3 Medium",
  "phi-3.5-mini": "Phi 3.5 Mini",
  "phi-3.5-moe": "Phi 3.5 MoE",
  "phi-4": "Phi 4",
  "phi-4-mini": "Phi 4 Mini",
  "phi-4-multimodal": "Phi 4 Multimodal",
  "wizardlm": "WizardLM",
  "wizardcoder": "WizardCoder",
  "orca-2": "Orca 2",
  "yi": "Yi",
  "yi-1.5": "Yi 1.5",
  "yi-6b": "Yi 6B",
  "yi-9b": "Yi 9B",
  "yi-34b": "Yi 34B",
  "yi-coder": "Yi Coder",
  "yi-large": "Yi Large",
  "yi-vision": "Yi Vision",
  "starcoder": "StarCoder",
  "starcoder2": "StarCoder 2",
  "starcoderbase": "StarCoder Base",
  "magicoder": "Magicoder",
  "granite-code": "Granite Code",
  "granite-3b-code": "Granite 3B Code",
  "granite-8b-code": "Granite 8B Code",
  "command": "Command",
  "command-r": "Command R",
  "command-r-plus": "Command R+",
  "command-r7b": "Command R7B",
  "command-light": "Command Light",
  "command-nightly": "Command Nightly",
  "c4ai-command-r-plus": "Command R+",
  "c4ai-command-r": "Command R",
  "falcon": "Falcon",
  "falcon-7b": "Falcon 7B",
  "falcon-40b": "Falcon 40B",
  "falcon-180b": "Falcon 180B",
  "falcon3": "Falcon 3",
  "falcon-mamba": "Falcon Mamba",
  "baichuan": "Baichuan",
  "baichuan2": "Baichuan 2",
  "baichuan2-7b": "Baichuan 2 7B",
  "baichuan2-13b": "Baichuan 2 13B",
  "baichuan3": "Baichuan 3",
  "internlm": "InternLM",
  "internlm2": "InternLM 2",
  "internlm2.5": "InternLM 2.5",
  "internlm3": "InternLM 3",
  "mimo": "MiMo",
  "mimo-7b": "MiMo 7B",
  "grok-1": "Grok 1",
  "grok-1.5": "Grok 1.5",
  "grok-2": "Grok 2",
  "grok-2-mini": "Grok 2 Mini",
  "grok-2-vision": "Grok 2 Vision",
  "grok-3": "Grok 3",
  "grok-3-mini": "Grok 3 Mini",
  "grok-4": "Grok 4",
  "claude-3-opus": "Claude 3 Opus",
  "claude-3-sonnet": "Claude 3 Sonnet",
  "claude-3-haiku": "Claude 3 Haiku",
  "claude-3.5-sonnet": "Claude 3.5 Sonnet",
  "claude-3-5-sonnet": "Claude 3.5 Sonnet",
  "claude-3.5-haiku": "Claude 3.5 Haiku",
  "claude-3-5-haiku": "Claude 3.5 Haiku",
  "claude-3.7-sonnet": "Claude 3.7 Sonnet",
  "claude-3-7-sonnet": "Claude 3.7 Sonnet",
  "claude-3.5-opus": "Claude 3.5 Opus",
  "claude-3-5-opus": "Claude 3.5 Opus",
  "claude-4-opus": "Claude 4 Opus",
  "claude-4-sonnet": "Claude 4 Sonnet",
  "claude-4.5-sonnet": "Claude 4.5 Sonnet",
  "claude-4.5-opus": "Claude 4.5 Opus",
  "claude-4.5-haiku": "Claude 4.5 Haiku",
  "claude-4-5-opus": "Claude 4.5 Opus",
  "claude-4-5-sonnet": "Claude 4.5 Sonnet",
  "claude-4-5-haiku": "Claude 4.5 Haiku",
  "claude-sonnet-4": "Claude Sonnet 4",
  "claude-opus-4": "Claude Opus 4",
  "claude-haiku-4": "Claude Haiku 4",
  "claude-sonnet-4.5": "Claude Sonnet 4.5",
  "claude-opus-4.5": "Claude Opus 4.5",
  "claude-haiku-4.5": "Claude Haiku 4.5",
  "claude-5-opus": "Claude 5 Opus",
  "claude-5-sonnet": "Claude 5 Sonnet",
  "claude-5-haiku": "Claude 5 Haiku",
  "claude-opus-5": "Claude Opus 5",
  "claude-sonnet-5": "Claude Sonnet 5",
  "claude-haiku-5": "Claude Haiku 5",
  "claude-fable-5": "Claude Fable 5",
  "claude-opus-5.5": "Claude Opus 5.5",
  "claude-5.5-opus": "Claude Opus 5.5",
  "claude-fable-5.1": "Claude Fable 5.1",
  "claude-mythos-5.1": "Claude Mythos 5.1",
  "gpt-3.5": "GPT-3.5",
  "gpt-3.5-turbo": "GPT-3.5 Turbo",
  "gpt-4": "GPT-4",
  "gpt-4-turbo": "GPT-4 Turbo",
  "gpt-4o": "GPT-4o",
  "gpt-4o-mini": "GPT-4o Mini",
  "gpt-4.1": "GPT-4.1",
  "gpt-4.1-mini": "GPT-4.1 Mini",
  "gpt-4.1-nano": "GPT-4.1 Nano",
  "gpt-5": "GPT-5",
  "gpt-5-mini": "GPT-5 Mini",
  "gpt-5-nano": "GPT-5 Nano",
  "gpt-5-codex": "GPT-5 Codex",
  "gpt-5.1": "GPT-5.1",
  "gpt-5.1-mini": "GPT-5.1 Mini",
  "gpt-5.1-pro": "GPT-5.1 Pro",
  "gpt-5.1-codex": "GPT-5.1 Codex",
  "chatgpt-5.6": "ChatGPT 5.6",
  "chatgpt-5.6-mini": "ChatGPT 5.6 Mini",
  "chatgpt-5.6-nano": "ChatGPT 5.6 Nano",
  "gpt-5.6": "GPT-5.6",
  "gpt-5.6-sol": "GPT-5.6 Sol",
  "gpt-6": "GPT-6",
  "gpt-6-astra": "GPT-6 Astra",
  "gpt-6-sol": "GPT-6 Sol",
  "gpt-6-luna": "GPT-6 Luna",
  "o1": "o1",
  "o1-mini": "o1 Mini",
  "o1-preview": "o1 Preview",
  "o1-pro": "o1 Pro",
  "o3": "o3",
  "o3-mini": "o3 Mini",
  "o3-pro": "o3 Pro",
  "o4-mini": "o4 Mini",
  "o5": "o5",
  "o5-mini": "o5 Mini",
  "o5-pro": "o5 Pro",
  "chatgpt-4o-latest": "ChatGPT-4o",
  "gpt-oss": "ChatGPT 4",
  "gpt-oss-20b": "ChatGPT 4 Nano",
  "gpt-oss-120b": "ChatGPT 4",
  "text-embedding-3-small": "Embedding 3 Small",
  "text-embedding-3-large": "Embedding 3 Large",
  "text-embedding-nomic-embed-text-v1.5": "Nomic Embed V1.5",
  "nous-hermes": "Nous Hermes",
  "openhermes": "OpenHermes",
  "dolphin": "Dolphin",
  "dolphin-mixtral": "Dolphin Mixtral",
  "solar": "Solar",
  "nanbeige": "Nanbeige",
  "numina-math": "NuminaMath",
  "skywork": "Skywork",
  "hunyuan": "Hunyuan",
  "hunyuan-pro": "Hunyuan Pro",
  "ernie": "ERNIE",
  "ernie-4": "ERNIE 4",
}));

// Provider display names, first keyword match (substring, lowercased) wins.
// Specific keywords before generic parents: `gpt-oss` before `gpt`, so
// `gpt-oss-20b` resolves to OpenAI without swallowing, and `command-r`
// before `command` so Cohere wins over nonsense.
const PROVIDER_BY_KEYWORD = [
  ["claude", "Anthropic"],
  ["gpt-oss", "OpenAI"],
  ["chatgpt", "OpenAI"],
  ["gpt-3.5", "OpenAI"],
  ["gpt-4", "OpenAI"],
  ["gpt-5", "OpenAI"],
  ["o1", "OpenAI"],
  ["o3", "OpenAI"],
  ["o4", "OpenAI"],
  ["gpt", "OpenAI"],
  ["gemini", "Google"],
  ["codegemma", "Google"],
  ["gemma", "Google"],
  ["qwen", "Alibaba"],
  ["qwq", "Alibaba"],
  ["qvq", "Alibaba"],
  ["codellama", "Meta"],
  ["llama", "Meta"],
  ["deepseek", "DeepSeek"],
  ["minimax", "MiniMax"],
  ["codestral", "Mistral AI"],
  ["pixtral", "Mistral AI"],
  ["ministral", "Mistral AI"],
  ["mixtral", "Mistral AI"],
  ["mistral", "Mistral AI"],
  ["grok", "xAI"],
  ["mimo", "Xiaomi"],
  ["glm", "Zhipu AI"],
  ["chatglm", "Zhipu AI"],
  ["kimi", "Moonshot"],
  ["moonshot", "Moonshot"],
  ["yi", "01.AI"],
  ["cohere", "Cohere"],
  ["c4ai-command", "Cohere"],
  ["command-r", "Cohere"],
  ["command", "Cohere"],
  ["falcon", "TII"],
  ["baichuan", "Baichuan"],
  ["internlm", "Shanghai AI Lab"],
  ["starcoder", "BigCode"],
  ["magicoder", "BigCode"],
  ["granite", "IBM"],
  ["phi", "Microsoft"],
  ["wizard", "Microsoft"],
  ["orca", "Microsoft"],
  ["nous-hermes", "Nous Research"],
  ["openhermes", "Nous Research"],
  ["dolphin", "Eric Hartford"],
  ["hunyuan", "Tencent"],
  ["ernie", "Baidu"],
  ["skywork", "Skywork"],
];

const PARAMETER_SIZE_PATTERNS = [
  /(\d+)x(\d+(?:\.\d+)?)[bm]/i,
  /(\d+\.\d+)[bm]/i,
  /(\d+)[bm]/i,
];

const QUANT_PATTERNS = [
  /\bq\d+_k_[sml]\b/i,
  /\bq\d+_k\b/i,
  /\bq\d+_[01]\b/i,
  /\bq[2-8]\b/i,
  /\b(?:fp16|fp32|bf16|int4|int8)\b/i,
  /\b(?:awq|gptq|exl2|gguf|ggml|bnb)\b/i,
];

const VARIANT_SUFFIXES = new Set([
  "instruct", "chat", "base", "it", "hf",
  "awq", "gptq", "exl2", "fp16", "bf16", "fp32", "cloud",
]);

const VERSION_PATTERNS = [/^v\d+(\.\d+)+$/, /^\d+\.\d+\.\d+.*$/];

function canonicalSize(raw) {
  const moe = raw.toLowerCase()
    .replace(/(\d+)x(\d+(?:\.\d+)?)([bm])/i, (_, count, perExpert, unit) => `${count}x${perExpert}${unit.toUpperCase()}`);
  return /[bm]$/.test(moe) ? moe.slice(0, -1) + moe.slice(-1).toUpperCase() : moe;
}

function extractParameterSize(name) {
  for (const pattern of PARAMETER_SIZE_PATTERNS) {
    const match = pattern.exec(name);
    if (match) {
      return canonicalSize(match[0]);
    }
  }
  return null;
}

function extractQuant(name) {
  for (const pattern of QUANT_PATTERNS) {
    const match = pattern.exec(name);
    if (match) {
      return match[0].toUpperCase();
    }
  }
  return null;
}

function buildLookupKey(name) {
  let working = name;
  for (; ;) {
    const lastDash = working.lastIndexOf("-");
    if (lastDash > 0) {
      const tailLower = working.slice(lastDash + 1).toLowerCase();
      if (VARIANT_SUFFIXES.has(tailLower) || VERSION_PATTERNS.some((p) => p.test(tailLower))) {
        working = working.slice(0, lastDash);
        continue;
      }
    }
    const moeStripped = working.replace(/-\d+x\d+(?:\.\d+)?[bm]$/i, "");
    if (moeStripped !== working) {
      working = moeStripped;
      continue;
    }
    const sizeStripped = working.replace(/-\d+(?:\.\d+)?[bm]$/i, "");
    if (sizeStripped !== working) {
      working = sizeStripped;
      continue;
    }
    let quantStripped = null;
    for (const pattern of QUANT_PATTERNS) {
      const match = pattern.exec(working);
      if (match && match.index + match[0].length === working.length) {
        quantStripped = working.slice(0, match.index).replace(/-+$/, "");
        break;
      }
    }
    if (quantStripped !== null) {
      working = quantStripped;
      continue;
    }
    return working.toLowerCase();
  }
}

function lookupDisplayName(key) {
  if (!key) {
    return null;
  }
  let hit = MODEL_DISPLAY_NAMES.get(key);
  if (hit !== undefined) {
    return hit;
  }
  if (key.includes("-")) {
    hit = MODEL_DISPLAY_NAMES.get(key.replaceAll("-", ""));
    if (hit !== undefined) {
      return hit;
    }
  }
  if (!key.includes("-")) {
    const dashInserted = key.replace(/(?<=[a-z])(?=\d)/g, "-");
    if (dashInserted !== key) {
      hit = MODEL_DISPLAY_NAMES.get(dashInserted);
      if (hit !== undefined) {
        return hit;
      }
      let current = dashInserted;
      while (current.includes("-")) {
        current = current.slice(0, current.lastIndexOf("-"));
        hit = MODEL_DISPLAY_NAMES.get(current);
        if (hit !== undefined) {
          return hit;
        }
      }
    }
    return null;
  }
  let current = key;
  while (current.includes("-")) {
    current = current.slice(0, current.lastIndexOf("-"));
    hit = MODEL_DISPLAY_NAMES.get(current);
    if (hit !== undefined) {
      return hit;
    }
  }
  return null;
}

function lookupDisplayNameWithSize(key, parameterSize) {
  if (parameterSize) {
    const sizeQualified = `${key}-${parameterSize.toLowerCase()}`;
    const sizeQualifiedDisplay = MODEL_DISPLAY_NAMES.get(sizeQualified);
    const bareDisplay = MODEL_DISPLAY_NAMES.get(key);
    if (sizeQualifiedDisplay !== undefined && bareDisplay !== undefined &&
      sizeQualifiedDisplay !== `${bareDisplay} ${parameterSize}`) {
      return sizeQualifiedDisplay;
    }
  }
  return lookupDisplayName(key);
}

function fallbackDisplay(key) {
  return key.split("-").map((part) => part ? part[0].toUpperCase() + part.slice(1) : "").join(" ");
}

const OLLAMA_TRAILING_SUFFIXES = /-(?:\d+(?:\.\d+)?[bm]|chat|instruct|base|it|hf|cloud|\d+k)$/i;

function resolveGlmDisplay(key) {
  if (!key.startsWith("glm")) {
    return null;
  }
  let working = key;
  for (; ;) {
    const next = working.replace(OLLAMA_TRAILING_SUFFIXES, "");
    if (next === working) {
      break;
    }
    working = next;
  }
  return working.split("-").flatMap((part) => {
    const lower = part.toLowerCase();
    if (lower === "glm") {
      return ["glm"];
    }
    if (lower.startsWith("glm") && lower.length > 3) {
      return ["glm", lower.slice(3)];
    }
    return [part];
  }).map((part) => part.toLowerCase() === "glm" ? "GLM" : part[0].toUpperCase() + part.slice(1)).join(" ");
}

export function parseModelName(raw) {
  const rawTrimmed = raw.trim();

  const colonIndex = rawTrimmed.indexOf(":");
  const beforeColon = colonIndex === -1 ? rawTrimmed : rawTrimmed.slice(0, colonIndex);
  const afterColon = colonIndex === -1 ? "" : rawTrimmed.slice(colonIndex + 1);

  const slashIndex = beforeColon.lastIndexOf("/");
  const baseName = slashIndex === -1 ? beforeColon : beforeColon.slice(slashIndex + 1);

  const dateStripped = baseName
    .replace(/-\d{8}$/, "")
    .replace(/-\d{4}-\d{2}-\d{2}$/, "");

  const combined = afterColon.trim() ? `${dateStripped}-${afterColon}` : dateStripped;
  const parameterSize = extractParameterSize(combined);
  const quant = extractQuant(combined);

  const lookupKey = buildLookupKey(dateStripped);

  const resolvedDisplay = resolveGlmDisplay(lookupKey)
    ?? lookupDisplayNameWithSize(lookupKey, parameterSize)
    ?? (lookupKey === "" ? rawTrimmed : fallbackDisplay(lookupKey));

  const lowerName = rawTrimmed.toLowerCase();
  const provider = PROVIDER_BY_KEYWORD.find(([keyword]) => lowerName.includes(keyword))?.[1] ?? null;

  return {
    displayName: resolvedDisplay,
    parameterSize,
    quant,
    provider,
    isFromCatalog: MODEL_DISPLAY_NAMES.has(lookupKey),
    rawName: rawTrimmed,
  };
}

const PROVIDER_WIRE_NAMES = new Map(Object.entries({
  "ollama": "Ollama",
  "openai": "OpenAI",
  "deepseek": "DeepSeek",
  "minimax": "MiniMax",
  "lmstudio": "LM Studio",
}));

export function beautifyProvider(raw) {
  if (!raw) {
    return "";
  }
  const known = PROVIDER_WIRE_NAMES.get(raw.toLowerCase());
  if (known) {
    return known;
  }
  return raw.split(/[\s_-]+/).filter(Boolean).map((part) => part[0].toUpperCase() + part.slice(1)).join(" ");
}
