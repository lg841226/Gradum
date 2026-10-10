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

The local HTTP endpoint is not open: by default it binds loopback only and answers only requests whose `Host`
header names a loopback address and that carry the per-machine bearer token. The one opt-in that changes this is
`"server.allowRemote": true` together with a non-loopback `server.host`, which lets the server answer its own
machine's addresses on the local network — still only requests carrying the token. The runtime generates that token
on first start and stores it at `~/.gradum/server.token` with owner-only permissions (`0600`);
it can be overridden with the `GRADUM_SERVER_TOKEN` environment variable. Without a token, a request can only
read the skill editor in read-only viewer mode; editing unlocks either with the token or — from another device —
with a five-character pairing code that the server prints to the host's console at startup and regenerates on every
restart. Only the `/health` liveness route and the pairing-code exchange itself are reachable without a token.

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
