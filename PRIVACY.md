# Privacy Policy

Gradum is a local-first tool. This page describes what the Gradum IntelliJ IDEA plugin does
with your data.

## What the plugin collects

Nothing. The plugin does not collect analytics, telemetry, crash reports, or usage statistics.
It has no account system and no cloud service behind it.

## What the plugin sends

The plugin communicates only with the Gradum runtime that you run on your own machine, over
HTTP on localhost (default `http://localhost:8765`). Your code, prompts, and attachments are
sent to that local runtime. They are not sent to the plugin authors.

## Where your data goes

The Gradum runtime runs on your machine. When you configure a model provider, the runtime sends
your requests directly to that provider's endpoint. Depending on the provider you choose, this
may be a local server (for example Ollama, LM Studio, vLLM, or LocalAI) or a hosted service
(for example Zhipu BigModel, DeepSeek, or MiniMax). Data sent to a hosted provider is handled
under that provider's own terms and privacy policy. If you enable the web search skill, it calls
the Tavily API, and your search queries are sent to Tavily.

## Data on your machine

Conversation history and settings are stored locally by the Gradum runtime (by default under
`~/.gradum`). You control them and can delete them at any time.

## Third parties

The plugin authors receive nothing, and operate no server that your data passes through.

## Contact

Open an issue at https://github.com/lg841226/Gradum/issues
