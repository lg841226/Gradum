# Gradum

## A Local-First AI Coding Agent with Function Calling

### Technical White Paper

| Field                | Value                                                 |
|----------------------|-------------------------------------------------------|
| **Version**          | 0.8.2                                                 |
| **Status**           | Active development                                    |
| **Language Runtime** | Python 3                                              |
| **LLM Backend**      | Ollama + OpenAI-compatible (LM Studio, vLLM, LocalAI) |
| **Diagram Format**   | Mermaid                                               |
| **Last Revised**     | 2026-06-15                                            |

---

## Abstract

Gradum is a local-first AI coding agent that exposes a curated set of
file-system and shell tools to a large language model served by Ollama
or any OpenAI-compatible server (LM Studio, vLLM, LocalAI). It targets
code engineering tasks — reading, editing, and running code inside a
local repository — with an emphasis on **deterministic tool behavior**,
**observable execution**, and **defense against prompt injection**.

Unlike cloud-based agents that execute inside managed sandboxes, Gradum
runs on the operator's own machine with full filesystem and shell
access. The design therefore places significant weight on a dedicated
**Command Safety Filter** — a structural classifier that examines every
shell command *before* it reaches `subprocess.run(shell=True)` and
refuses catastrophic operations regardless of what the LLM requests.

This document describes the system's architecture, the rationale behind
its major subsystems, its current security model, and the limitations
that follow from its deployment assumptions.

---

## Table of Contents

1. [Introduction](#1-introduction)
   - [1.1 Motivation](#11-motivation)
   - [1.2 Audience](#12-audience)
   - [1.3 Scope](#13-scope)
2. [Design Principles and Goals](#2-design-principles-and-goals)
   - [2.1 Principles](#21-principles)
   - [2.2 Goals](#22-goals)
   - [2.3 Non-Goals](#23-non-goals)
3. [System Architecture](#3-system-architecture)
   - [3.1 Architecture Overview](#31-architecture-overview)
   - [3.2 Project Layout](#32-project-layout)
   - [3.3 Module Dependencies](#33-module-dependencies)
   - [3.4 Runtime Data Flow](#34-runtime-data-flow)
   - [3.5 Skill Auto-Discovery](#35-skill-auto-discovery)
   - [3.6 Registered Skills](#36-registered-skills)
   - [3.7 NDJSON Event Stream](#37-ndjson-event-stream)
   - [3.8 Context Persistence](#38-context-persistence)
   - [3.9 LLM Client Streaming](#39-llm-client-streaming)
   - [3.10 HTTP Server Layer](#310-http-server-layer)
   - [3.11 End-to-End View](#311-end-to-end-view)
4. [Key Subsystems](#4-key-subsystems)
   - [4.1 edit_file State Machine](#41-edit_file-state-machine)
   - [4.2 Command Safety Filter](#42-command-safety-filter)
   - [4.3 Detached Process Execution](#43-detached-process-execution)
5. [Security Model](#5-security-model)
   - [5.1 Threat Model](#51-threat-model)
   - [5.2 Defenses in Place](#52-defenses-in-place)
   - [5.3 Outstanding Risks](#53-outstanding-risks)
6. [Design Highlights](#6-design-highlights)
7. [Limitations](#7-limitations)
8. [Future Work](#8-future-work)
9. [Glossary](#9-glossary)
10. [Change Log](#10-change-log)

---

## 1. Introduction

### 1.1 Motivation

Existing AI coding assistants — hosted Copilots, cloud agent platforms —
impose constraints that are inappropriate for some workflows:

- **Latency-sensitive editing.** Round-trips to remote inference add
  visible delay to interactive sessions.
- **Confidential code.** Sending source files to third-party servers
  may be unacceptable for proprietary, regulated, or
  research-in-progress codebases.
- **Reproducible tooling.** Cloud agents hide their tool
  implementations behind proprietary APIs; operators cannot audit or
  modify them.
- **Local compute.** Operators with a local GPU may have ample capacity
  that is wasted if every inference must be paid for remotely.

Gradum addresses these by running a local LLM — via Ollama or any
OpenAI-compatible server — with tools implemented as plain Python
classes on the operator's machine. The operator retains the ability to
read, edit, debug, and version every component of the agent itself.

### 1.2 Audience

This document is written for three primary audiences:

- **Operators** running Gradum, who need to understand its safety
  guarantees, its configuration surface, and its limitations.
- **Contributors** adding new skills, modifying the agent loop, or
  extending the security model.
- **Reviewers** evaluating whether Gradum's threat model fits their
  use case before adoption.

### 1.3 Scope

**In scope.** Agent loop design; skill auto-discovery mechanism;
model auto-discovery and multi-provider support; NDJSON event stream
format; context persistence and simplification; Command Safety Filter
design and block lists; CLI surface area.

**Out of scope.** Model selection guidance; prompt engineering tips;
Ollama installation and tuning; comparison with other agents; deployment
recipes for production environments.

---

## 2. Design Principles and Goals

### 2.1 Principles

The following principles govern the design of every subsystem:

1. **Local-first.** No network dependencies beyond the local Ollama
   server. Filesystem and shell are the only external surfaces the
   agent touches directly.
2. **Observable by default.** Every meaningful state transition emits
   a structured NDJSON event to stdout, enabling log replay,
   time-travel debugging, and downstream UI rendering without coupling
   to internal state.
3. **Tools are plain code.** A skill is a Python class subclassing
   `Skill`. There is no DSL, no plugin manifest, no remote registry —
   the skill surface is the filesystem of the project.
4. **Defence in depth at the skill layer.** The system prompt is
   *advisory*: it can be edited, corrupted, or successfully attacked.
   Safety-critical enforcement therefore lives in the skill
   implementation itself, not in the prompt.
5. **Symmetric failure handling.** Every skill returns the same dict
   shape — `{success, tool, ...}` on success or `{success: false, tool,
   error: {code, message}}` on failure — enabling uniform agent-loop
   behavior regardless of which skill failed. The `tool` field always
   identifies the skill that produced the result.
6. **No silent confirmation.** Because no UI is present in the
   reference deployment, "needs confirmation" is collapsed into
   "blocked". This avoids creating the illusion of safety when no
   operator is actually reviewing the prompt.

### 2.2 Goals

- [x] Execute code-engineering tasks on a local repository end-to-end
  (read → plan → edit → test → run)
- [x] Stream LLM responses for low perceived latency
- [x] Persist session context across runs for continuity
- [x] Refuse catastrophic shell commands even when the model is tricked
- [x] Cross-platform support (macOS, Linux, Windows with encoding
  fallback)

### 2.3 Non-Goals

- [ ] Multi-user or multi-tenant operation
- [ ] Sandboxed execution (assumes the operator's own machine)
- [ ] In-agent confirmation UI
- [ ] Plugin marketplace (skills are versioned in-tree)
- [ ] Multi-modal input (text only)

---

## 3. System Architecture

### 3.1 Architecture Overview

Gradum is organized as six cooperating layers:

| Layer                 | Responsibility                                              | Implementation                                               |
|-----------------------|-------------------------------------------------------------|--------------------------------------------------------------|
| **User Layer**        | CLI invocation, HTTP clients, stdout consumption            | `argparse`, HTTP/WebSocket clients, NDJSON consumers         |
| **Server Layer**      | HTTP API, streaming responses                               | `server/app.py`, `server/routes.py`, `server/config.py`      |
| **Application Layer** | Agent loop, message bookkeeping                             | `agent.py:Agent`                                             |
| **LLM I/O Layer**     | HTTP transport, streaming, token accounting, multi-provider | `client.py:OllamaClient`, `client.py:OpenAICompatibleClient` |
| **Skills Layer**      | Per-tool execution, auto-discovery                          | `skills/*.py`                                                |
| **Utility Layer**     | Cross-cutting concerns: context persistence, command safety | `utils/io_utils.py`, `utils/command_filter.py`               |

A consolidated end-to-end view is provided in §3.11. The following
subsections drill into each aspect individually.

### 3.2 Project Layout

```mermaid
graph LR
    Root["Gradum/<br/>Project Root"]

    Root --> Agent["agent.py<br/>Main Entry Agent Class"]
    Root --> Client["client.py<br/>Ollama + OpenAI Clients"]
    Root --> Discovery["discovery.py<br/>Model Auto-Discovery"]
    Root --> ServerDir["server/"]

    Root --> SkillsDir["skills/"]
    Root --> Utils["utils/"]
    Root --> Prompts["prompts/"]
    Root --> Output["output/"]
    Root --> Scripts["scripts/"]
    Root --> Tmp["tmp/"]
    Root --> Archived["_archived/<br/>(Historical Archive)"]

    ServerDir --> SV_Init["__init__.py<br/>Server Module"]
    ServerDir --> SV_App["app.py<br/>FastAPI Application"]
    ServerDir --> SV_Routes["routes.py<br/>HTTP Routes"]
    ServerDir --> SV_Config["config.py<br/>Server Config"]
    ServerDir --> SV_Main["__main__.py<br/>Server Entry Point"]

    SkillsDir --> SK_Init["__init__.py<br/>Skills Registry"]
    SkillsDir --> SK_Base["base.py<br/>Skill Base Class"]
    SkillsDir --> SK_Read["read_file.py<br/>ReadFileSkill"]
    SkillsDir --> SK_Edit["edit_file.py<br/>EditFileSkill"]
    SkillsDir --> SK_Save["save_file.py<br/>SaveFileSkill"]
    SkillsDir --> SK_Cmd["run_cmd.py<br/>RunCmdSkill"]
    SkillsDir --> SK_Search["search.py<br/>SearchSkill"]
    SkillsDir --> SK_Todo["todo.py<br/>TodoManager + TodoSkill"]
    SkillsDir --> SK_Finish["complete_plan.py<br/>CompletePlanSkill"]

    Utils --> IO["io_utils.py<br/>ContextManager"]
    Utils --> CF["command_filter.py<br/>Command Safety Filter"]

    Prompts --> SysPrompt["system_prompt.md"]

    Output --> Ctx["context.json"]
    Output --> Log["log.txt"]

    Scripts --> Bsh["backup.sh<br/>(macOS)"]
    Scripts --> Bps1["backup.ps1<br/>(Windows)"]

    Archived --> Launcher["launcher/<br/>Old Qt GUI"]
    Launcher -.->|"deprecated"| NoUse["Not Used"]

    classDef active fill:#e1f5e1,stroke:#2e7d32,color:#000
    classDef archive fill:#f5f5f5,stroke:#9e9e9e,color:#616161,stroke-dasharray: 5
    classDef data fill:#fff3e0,stroke:#ef6c00,color:#000
    classDef server fill:#e3f2fd,stroke:#1565c0,color:#000

    class Agent,Client,Discovery,SK_Init,SK_Base,SK_Read,SK_Edit,SK_Save,SK_Cmd,SK_Search,SK_Todo,SK_Finish,IO,CF,SysPrompt,Bsh,Bps1 active
    class SV_Init,SV_App,SV_Routes,SV_Session,SV_Config,SV_Main server
    class Ctx,Log data
    class Launcher,NoUse archive
```

The layout separates **agent code** (`agent.py`, `client.py`) from
**server code** (`server/`) from **tools** (`skills/`) from
**utilities** (`utils/`). This split lets contributors add a new skill
without touching the agent loop, add HTTP endpoints without touching
the agent core, and lets operators audit tool behavior by reading a
single directory.

### 3.3 Module Dependencies

```mermaid
graph TD
    Agent["agent.py<br/>Agent Class"]:::core

    Client["client.py<br/>OllamaClient + OpenAICompatibleClient + AgentConfig"]:::core
    Discovery["discovery.py<br/>Model Auto-Discovery"]:::core
    Skills["skills/__init__.py<br/>Skills Container"]:::core
    SKBase["skills/base.py<br/>Skill Base Class"]:::core
    TodoMgr["skills/todo.py<br/>TodoManager (Singleton)"]:::core
    IO["utils/io_utils.py<br/>ContextManager"]:::core

    ServerApp["server/app.py<br/>FastAPI Application"]:::server
    ServerRoutes["server/routes.py<br/>HTTP Routes"]:::server
    ServerConfig["server/config.py<br/>Server Config"]:::server

    ReadS["skills/read_file.py<br/>ReadFileSkill"]:::skill
    EditS["skills/edit_file.py<br/>EditFileSkill"]:::skill
    SaveS["skills/save_file.py<br/>SaveFileSkill"]:::skill
    CmdS["skills/run_cmd.py<br/>RunCmdSkill"]:::skill
    SearchS["skills/search.py<br/>SearchSkill"]:::skill
    TodoS["skills/todo.py<br/>TodoSkill"]:::skill
    FinishS["skills/complete_plan.py<br/>CompletePlanSkill"]:::skill

    CmdFilter["utils/command_filter.py<br/>classify(command) → Verdict"]:::util

    SysPrompt["prompts/system_prompt.md"]:::data
    CtxFile[("output/context.json")]:::data
    LogFile[("output/log.txt")]:::data
    Ollama["Ollama Server<br/>http://localhost:11434"]:::ext
    FS["Local File System"]:::ext
    HTTPClient["HTTP Client<br/>curl / browser / IDE"]:::ext

    Agent --> Client
    Agent --> Discovery
    Agent --> Skills
    Agent --> IO
    Agent --> TodoMgr
    Agent --> SysPrompt

    ServerApp --> ServerConfig
    ServerApp --> ServerRoutes
    ServerRoutes --> Agent
    ServerRoutes --> Discovery
    ServerRoutes --> Skills

    Client -->|"HTTP /api/chat or /v1/chat/completions"| Ollama

    Skills --> SKBase
    Skills -->|"auto-discover"| ReadS
    Skills -->|"auto-discover"| EditS
    Skills -->|"auto-discover"| SaveS
    Skills -->|"auto-discover"| CmdS
    Skills -->|"auto-discover"| SearchS
    Skills -->|"auto-discover"| TodoS
    Skills -->|"auto-discover"| FinishS

    ReadS --> SKBase
    EditS --> SKBase
    SaveS --> SKBase
    CmdS --> SKBase
    SearchS --> SKBase
    TodoS --> SKBase
    FinishS --> SKBase
    FinishS --> TodoMgr

    CmdS -->|"classify() before exec"| CmdFilter
    ReadS --> FS
    EditS --> FS
    SaveS --> FS
    CmdS -->|"subprocess"| FS
    SearchS -->|"os.walk"| FS

    IO --> CtxFile
    Agent -.->|"NDJSON stdout"| LogFile
    ServerRoutes -.->|"NDJSON HTTP stream"| HTTPClient

    classDef core fill:#bbdefb,stroke:#1565c0,color:#000
    classDef server fill:#e3f2fd,stroke:#1565c0,color:#000
    classDef skill fill:#c8e6c9,stroke:#2e7d32,color:#000
    classDef data fill:#fff3e0,stroke:#ef6c00,color:#000
    classDef ext fill:#f3e5f5,stroke:#6a1b9a,color:#000
    classDef util fill:#fff8e1,stroke:#ff8f00,color:#000
```

Three dependency invariants worth noting:

- **The agent loop never imports a skill directly.** All skill access
  goes through the `Skills` registry, which is built once at startup
  via auto-discovery.
- **The server layer sits above the agent, not inside it.** HTTP
  routes call the agent, but the agent remains unaware of HTTP. This
  keeps the agent core reusable for both CLI and HTTP contexts.
- **The Command Safety Filter is the only safety-critical edge in the
  graph.** It sits between `RunCmdSkill` and `subprocess.run`. No
  other path reaches the shell.

### 3.4 Runtime Data Flow

```mermaid
sequenceDiagram
    autonumber
    participant U as User/CLI
    participant D as Discovery
    participant A as Agent
    participant CM as ContextManager
    participant C as LLMClient
    participant O as LLM Server
    participant S as Skills
    participant CF as CommandFilter
    participant FS as File System
    participant T as TodoManager
    participant E as stdout (NDJSON)

    U->>A: gradum [-m model] [-c] [-t] "task"
    activate A

    A->>D: discover_models()
    D->>O: GET /api/tags or /v1/models (per server)
    O-->>D: Model list
    D-->>A: list[ModelEntry]
    A->>E: emit models_discovered

    A->>A: Resolve provider, base_url, model

    A->>A: Load system_prompt.md
    Note over A: Replace {{OS}} placeholder

    opt --context flag
        A->>CM: load()
        CM-->>A: History messages (≤30)
    end

    A->>E: emit session_start
    A->>A: messages.append(user_input)
    A->>A: cached_tool_schemas = Skills.get_schemas()

    loop Main Loop (while True)
        A->>C: chat(messages, tools, stream=True)
        activate C
        C->>O: POST /api/chat (NDJSON stream)
        O-->>C: Stream chunk (content / tool_calls / thinking)
        C-->>A: yield (content, tool_calls, thinking)

        alt thinking exists
            A->>E: emit thinking
        end

        alt turn_text exists
            A->>E: emit llm_response
        end

        alt tool_calls is empty
            A->>A: break (end loop)
        else tool_calls exists
            A->>A: _prepare_tool_calls (assign tool_call_id)
            A->>A: messages.append(assistant + tool_calls)

            loop each tool_call
                A->>S: skills.get(tool_name)
                S-->>A: Skill instance

                alt tool_name == "run_cmd"
                    A->>CF: classify(command)
                    CF-->>A: Verdict {safe / blocked}
                    opt verdict.is_blocked
                        A->>A: skip subprocess, return COMMAND_BLOCKED
                    end
                end

                A->>S: skill.execute(**args)
                S->>FS: read/write / subprocess / search
                FS-->>S: result
                S-->>A: {success, ...}
                A->>E: emit tool_call
                opt failure
                    A->>E: emit error
                end
                A->>A: messages.append(tool + tool_call_id)

                A->>T: get_reminder()
                T-->>A: Reminder text (if any)
                opt has reminder
                    A->>A: messages.append(tool reminder)
                end
            end

            Note over A: Continue next LLM call
        end
        deactivate C
    end

    A->>E: emit session_end (with token stats)
    A->>CM: save(messages, model, full_read_files)
    CM->>FS: Write output/context.json
    deactivate A

    Note over U,E: Entire session outputs to stdout as NDJSON event stream
```

The flow begins with model auto-discovery: the agent probes known
local servers (Ollama on :11434, LM Studio on :1234, vLLM on :8000,
LocalAI on :8080) and collects all available models. Each server probe
retries up to 2 times on transient errors (timeout, connection
refused) with exponential backoff. The user can specify a model via
`--model` to select from the discovered list, or omit it to use the
default. The agent then resolves the appropriate provider and base_url
automatically.

The main loop is straightforward: stream from the LLM, dispatch tool
calls, append results, repeat until the model produces no tool calls.
The notable injection point is the per-tool-call branch that runs
`CommandFilter.classify()` before allowing `RunCmdSkill` to invoke
`subprocess`.

### 3.5 Skill Auto-Discovery

```mermaid
flowchart TD
    Start([Skills.__init__]) --> Discover[_discover_skills]

    Discover --> Glob["Path.glob('*.py')"]
    Glob --> Filter{Filter by filename}
    Filter -->|starts with _| Skip[Skip]
    Filter -->|== base| Skip
    Filter -->|regular .py| Import["importlib.import_module<br/>(skills.{stem})"]

    Import --> Inspect["inspect.getmembers(module)"]
    Inspect --> ClassCheck{Is Skill<br/>subclass?}
    ClassCheck -->|No| Next1[Continue iteration]
    ClassCheck -->|Yes obj is not Skill| Inst["obj() instantiate"]
    Inst --> Reg["self.register(skill)"]
    Reg --> Store["_skills[skill.name] = instance"]
    Store --> Next1

    Next1 --> More{More<br/>classes?}
    More -->|Yes| ClassCheck
    More -->|No| Done([Registration complete])

    Done --> GetSchema["get_schemas()<br/>→ Collect all skill.get_schema()"]
    Done --> Get["get(name)<br/>→ Return skill instance"]

    GetSchema --> AgentUse[Agent calls]
    Get --> AgentUse

    classDef proc fill:#e3f2fd,stroke:#1565c0,color:#000
    classDef cond fill:#fff9c4,stroke:#f9a825,color:#000
    classDef store fill:#c8e6c9,stroke:#2e7d32,color:#000

    class Discover,Glob,Import,Inspect,Inst,Reg,Store,GetSchema,Get proc
    class Filter,ClassCheck,More cond
```

Skills are discovered by directory scan plus reflection. There is no
central manifest. This trades *discoverability* (no list of registered
tools) for *extensibility* (adding a skill is `git add skills/foo.py`).

### 3.6 Registered Skills

```mermaid
graph TB
    Skills["Skills Container<br/>(_skills dict)"]:::core

    Skills --> S1["read_file<br/>alias: Read<br/>Read file (full/line range)"]:::skill
    Skills --> S2["edit_file<br/>alias: Edited<br/>search-replace edit"]:::skill
    Skills --> S3["save_file<br/>alias: Saved<br/>Write file"]:::skill
    Skills --> S4["run_cmd<br/>alias: Ran<br/>shell command<br/>(blocking / detached)"]:::skill
    Skills --> S5["search<br/>alias: Explored<br/>content/filename/dir search"]:::skill
    Skills --> S6["to_do<br/>alias: Planned<br/>Initialize task list"]:::skill
    Skills --> S7["finish_to_do_item<br/>alias: Completed<br/>Mark complete"]:::skill

    S6 -.->|"shared _todo_manager"| S7

    S1 -->|"returns content_hash<br/>+ line_range"| S1Note["Result fields:<br/>success, path, line_range,<br/>total_lines, content_hash, content"]
    S2 --> S2Note["Mode: sequential / atomic<br/>Error codes: CODE_NOT_FOUND,<br/>MULTIPLE_MATCHES, EMPTY_RESULT"]
    S3 --> S3Note["Auto mkdir -p parent dir<br/>returns bytes_written, created"]
    S4 --> S4Note["Two modes: blocking (45s) /<br/>detached (auto for GUI/dev servers)<br/>Returns exit_code, stdout, stderr<br/>encoding: utf-8 / gbk<br/>⚠ Calls CommandFilter.classify() first<br/>See §4.3 for detached details"]
    S5 --> S5Note["TIMEOUT=120s<br/>MAX_FILES=600<br/>MAX_DEPTH=6<br/>HARD_MAX_RESULTS=20"]
    S6 --> S6Note["One-time init, no changes<br/>returns total_tasks, current_task"]
    S7 --> S7Note["Sequential + Batch mode<br/>prevent skip/rollback"]

    classDef core fill:#bbdefb,stroke:#1565c0,color:#000
    classDef skill fill:#c8e6c9,stroke:#2e7d32,color:#000
    classDef note fill:#fff8e1,stroke:#ff8f00,color:#000
```

The seven skills are partitioned into three functional groups:

- **Read/Write/Edit** — the primary "I am a code editor" surface
- **Search** — the LLM's only way to discover code without reading
  every file
- **Todo management** — long-horizon planning across multiple turns

### 3.7 NDJSON Event Stream

```mermaid
graph LR
    Agent[Agent]:::core -->|print json.dumps| Stdout[(stdout<br/>NDJSON)]:::data

    Stdout --> P1["Downstream Consumer<br/>(e.g., CLI/UI)"]:::ext
    Stdout --> P2[log.txt<br/>Session History]:::data

    subgraph Event Types
        E0["models_discovered<br/>{models: [{name, server, provider}]}"]:::evt
        E1["session_start<br/>{version, model, think,<br/>context_loaded, context_messages}"]:::evt
        E2["context_loaded<br/>{message_count}"]:::evt
        E3["thinking<br/>{content}"]:::evt
        E4["llm_response<br/>{content}"]:::evt
        E5["tool_call<br/>{tool, arguments,<br/>tool_call_id, success, result}"]:::evt
        E6["error<br/>{code, message, source/tool,<br/>tool_call_id}"]:::evt
        E7["session_end<br/>{version, elapsed_seconds,<br/>model, token_usage}"]:::evt
    end

    Stdout --> E0
    Stdout --> E1
    Stdout --> E2
    Stdout --> E3
    Stdout --> E4
    Stdout --> E5
    Stdout --> E6
    Stdout --> E7

    classDef core fill:#bbdefb,stroke:#1565c0,color:#000
    classDef data fill:#fff3e0,stroke:#ef6c00,color:#000
    classDef ext fill:#f3e5f5,stroke:#6a1b9a,color:#000
    classDef evt fill:#e8f5e9,stroke:#2e7d32,color:#000
```

Event structure:

Every event is a single JSON object emitted via `json.dumps(..., ensure_ascii=False)` followed by `print()` (`agent.py:18-25`), which writes one event per line in UTF-8:

```json
{
  "type": "session_start",
  "timestamp": "2026-06-07T10:23:45",
  "data": {  }
}
```

#### Envelope

| Field       | Type   | Description                                                                  |
|-------------|--------|------------------------------------------------------------------------------|
| `type`      | string | Event discriminator; one of the seven values listed in the diagram above     |
| `timestamp` | string | ISO-8601 local time (no timezone suffix) captured at the `_emit()` call site |
| `data`      | object | Type-specific payload; shape varies by `type` (see below)                    |

The `type` field is the discriminator; consumers can route events by
type without parsing the payload. The stream is **LF-delimited** —
each `print()` appends exactly one `\n` — and consumers should parse
it line-by-line rather than loading the full session into memory.

#### Per-event-type data payloads

| `type`              | `data` fields                                                                   | Emitted at             |
|---------------------|---------------------------------------------------------------------------------|------------------------|
| `models_discovered` | `models: [{name, server, provider}]`                                            | `agent.py:main()`      |
| `session_start`     | `version`, `model`, `think`, `context_loaded`, `context_messages`               | `agent.py:75`          |
| `context_loaded`    | `message_count`                                                                 | `agent.py:85`          |
| `thinking`          | `content`                                                                       | `agent.py:121`         |
| `llm_response`      | `content`                                                                       | `agent.py:134`         |
| `tool_call`         | `tool`, `arguments`, `tool_call_id`, `success`, `result`                        | `agent.py:227`         |
| `error`             | `code`, `message`, `source` (LLM-side) *or* `tool` + `tool_call_id` (tool-side) | `agent.py:128`, `:238` |
| `session_end`       | `version`, `elapsed_seconds`, `model`, `token_usage: {prompt, completion}`      | `agent.py:265`         |

The `tool_call.result` shape is skill-specific; see the per-skill
notes in §3.6 for the exact fields. `run_cmd` additionally returns
`detached`, `pid`, `log_path`, and `command` when in detached mode
(§4.3), or `exit_code`, `stdout`, `stderr`, and `timed_out` when
blocking.

The `error` event has two emission sites with different `data` shapes:

- **LLM-side errors** (e.g. Ollama HTTP failure) emit
  `{code, message, source: "llm"}` with no `tool_call_id`.
- **Tool-side errors** emit `{code, message, tool, tool_call_id}`
  where `tool` is the skill name and `code` is one of the documented
  error codes (`COMMAND_BLOCKED`, `FILE_NOT_FOUND`, `CODE_NOT_FOUND`,
  `MULTIPLE_MATCHES`, `EMPTY_RESULT`, `TIMEOUT`, `IO_ERROR`, etc.;
  see §6).

#### Ordering invariants

- `models_discovered` is always the first event of a session (before
  `session_start`).
- `session_start` is always the second event, after model resolution.
- `session_end` is always the last event; its presence means the
  agent finished cleanly (no `KeyboardInterrupt`, no fatal exception).
- `tool_call` events for the same LLM turn are emitted in the order
  the model returned them in `tool_calls[]`.
- An `error` event with `tool_call_id` is emitted immediately after
  the corresponding `tool_call` event with `success: false`.
- `_emit()` is fire-and-forget: a broken downstream pipe does not
  abort the agent. If stdout is closed, the agent logs to stderr and
  continues.

#### Correlation

The `tool_call_id` field is the primary correlation key. It is
generated once when the LLM returns a `tool_calls[]` array and reused
in three places:

1. The `tool_call` event's `data.tool_call_id` (the call itself).
2. The `error` event's `data.tool_call_id`, if the call failed.
3. The `tool` message appended to the conversation history, so the
   LLM can match results to calls on its next turn.

Consumers that replay the stream into a different tool runtime need
to preserve `tool_call_id` to keep this correlation intact.

#### Worked example

A session that reads one file and launches a dev server in detached
mode produces the following stream (one JSON object per line):

```json lines
{"type": "models_discovered", "timestamp": "2026-06-14T10:23:44", "data": {"models": [{"name": "minimax-m2.5:cloud", "server": "http://localhost:11434", "provider": "ollama"}, {"name": "codestral-7b", "server": "http://localhost:1234", "provider": "openai"}]}}
{"type": "session_start", "timestamp": "2026-06-14T10:23:45", "data": {"version": "0.5.0", "model": "minimax-m2.5:cloud", "think": false, "context_loaded": false, "context_messages": 0}}
{"type": "thinking", "timestamp": "2026-06-14T10:23:46", "data": {"content": "The user wants a dev server. I should first read package.json to confirm the start command, then run it in detached mode."}}
{"type": "llm_response", "timestamp": "2026-06-14T10:23:47", "data": {"content": "Reading package.json to find the dev script..."}}
{"type": "tool_call", "timestamp": "2026-06-14T10:23:47", "data": {"tool": "read_file", "arguments": {"path": "package.json"}, "tool_call_id": "call_1", "success": true, "result": {"success": true, "path": "package.json", "line_range": [1, 42], "total_lines": 42, "content_hash": "a3f5c1", "content": "{\"scripts\":{\"dev\":\"vite\"}}"}}}
{"type": "tool_call", "timestamp": "2026-06-14T10:23:49", "data": {"tool": "run_cmd", "arguments": {"command": "npm run dev", "detached": true}, "tool_call_id": "call_2", "success": true, "result": {"success": true, "detached": true, "pid": 42187, "log_path": "output/run_cmd/42187.log", "command": "npm run dev", "message": "Command executed successfully with no output."}}}
{"type": "llm_response", "timestamp": "2026-06-14T10:23:49", "data": {"content": "Dev server started with PID 42187. Tail the log at output/run_cmd/42187.log."}}
{"type": "session_end", "timestamp": "2026-06-14T10:24:30", "data": {"version": "0.5.0", "elapsed_seconds": 45.2, "model": "minimax-m2.5:cloud", "token_usage": {"prompt": 1240, "completion": 87}}}
```

The stream begins with `models_discovered` (listing all available
models from local servers), followed by the session lifecycle. The
detached `run_cmd` returns immediately with `detached: true`, a
PID, and a log path — the LLM never has to guess whether the command
"failed" because of empty stdout (§4.3).

#### Downstream consumption

A minimal consumer is a one-liner with `jq`:

```bash
# Watch the stream and only print LLM narrative responses
python agent.py "start the dev server" | jq -c 'select(.type == "llm_response") | .data.content'

# Count tool calls grouped by skill name
python agent.py "refactor utils" | jq -s 'map(select(.type=="tool_call") | .data.tool) | group_by(.) | map({tool: .[0], count: length})'
```

For richer UIs the envelope is small enough to be parsed
incrementally line-by-line without a JSON streaming library.

### 3.8 Context Persistence

```mermaid
flowchart TD
    A1[Agent.run starts]:::core
    A2[User input]:::core
    A3[LLM call]:::core
    A4[Execute read_file]:::core
    A5[Agent.run ends]:::core

    subgraph Runtime Tracking
        Track["full_read_files: set<br/>Records paths from read_file<br/>calls without line_range"]:::data
    end

    A4 -->|if line_range is empty| Track
    Track --> A3

    A1 -->|"load_context=True"| Load[ContextManager.load]:::io
    Load -->|Read output/context.json| File1[("output/context.json")]:::fs
    File1 -->|Decrypt messages with _encrypted flag| Decrypt[Custom auth encryption<br/>HMAC-SHA256 CTR + HMAC]
    Decrypt -->|Filter system/tool roles| Filter1[Keep user/assistant]
    Filter1 -->|Take last max_messages=30| MSGS1[History messages]
    MSGS1 --> A2

    A2 --> A3
    A3 --> A4
    A4 --> A3

    A5 --> Save[ContextManager.save]:::io
    Save -->|Filter messages system/tool| Filter2
    Filter2 -->|Iterate user/assistant/tool| Clean[_clean_messages]
    Clean --> Check{"Does tool content start with<br/>'Success: Read {path}'<br/>and path ∈ full_read_files?"}
    Check -->|Yes| Drop[Drop this message]
    Check -->|No| Keep[Keep]
    Drop --> Simp
    Keep --> Simp[_simplify_content:<br/>trim whitespace/normalize punctuation]
    Simp --> Encrypt["Encrypt user/assistant content<br/>Custom auth encryption<br/>HMAC-CTR + HMAC-SHA256<br/>Random nonce + Integrity tag"]
    Encrypt --> Out[("output/context.json<br/>Write encrypted content")]
```

The `full_read_files` optimisation is the most important detail: once a
file has been read in full, subsequent references in the context can
collapse to a one-line acknowledgement, saving context window budget
on long sessions.

**Context Encryption (v0.8.0):** All `user` and `assistant` messages are
encrypted before writing to `context.json` using a custom authenticated
encryption scheme built entirely from Python standard library modules
(hashlib, hmac, os). The scheme provides:

- **Confidentiality**: HMAC-SHA256 in CTR (counter) mode for encryption
- **Integrity**: HMAC-SHA256 authentication tag over entire token
- **Randomness**: 16-byte random nonce per encryption (via `os.urandom`)
- **Versioning**: Version byte (0x81) for future algorithm upgrades

Token format: `version (1B) + nonce (16B) + ciphertext (NB) + HMAC (32B)`

This prevents **context injection attacks** where an attacker modifies
the context file to inject fake conversation history. On load, messages
with the `_encrypted` flag are decrypted; messages that fail decryption
are logged as warnings and kept as-is.

The encryption key is derived via HMAC-based key derivation from a
built-in source string (or overridden via the `GRADUM_CONTEXT_KEY`
environment variable). Separate derived keys are used for encryption
and authentication (key separation).

**No external dependencies required** — uses only Python standard library.

### 3.9 LLM Client Streaming

```mermaid
sequenceDiagram
    autonumber
    participant A as Agent
    participant C as LLMClient
    participant R as requests.post
    participant O as LLM Server

    A->>C: chat(messages, tools, stream=True, think=bool)

    alt OllamaClient
        Note over C: Payload: model, messages, options<br/>(temperature/top_p/num_ctx/num_predict)<br/>+ tools + think
        C->>R: POST /api/chat (NDJSON stream)
    else OpenAICompatibleClient
        Note over C: Payload: model, messages<br/>(temperature/top_p/max_tokens)<br/>+ tools
        C->>R: POST /v1/chat/completions (SSE stream)
    end

    R->>O: HTTP request
    O-->>R: Stream

    loop Each chunk
        R-->>C: Content / tool_calls
        C->>C: Parse + accumulate
        opt has token count
            C->>C: _accumulate_token_stats
        end
        C-->>A: yield (content, tool_calls, thinking, is_error)
    end

    Note over C: Exception handling:<br/>Timeout / ConnectionError /<br/>HTTPError(404/500) /<br/>TooManyRedirects / JSONDecodeError<br/>→ yield friendly error message

Note over C: Retry logic:<br/>Transient errors (Timeout, ConnectionError,<br/>5xx) retry up to 2 times with<br/>exponential backoff (1s, 2s)
```

**Two client implementations:**

| Client                   | Protocol                      | Streaming            | Tool Calls Format                                |
|--------------------------|-------------------------------|----------------------|--------------------------------------------------|
| `OllamaClient`           | Ollama `/api/chat`            | NDJSON               | `arguments` as dict                              |
| `OpenAICompatibleClient` | OpenAI `/v1/chat/completions` | SSE (`data:` prefix) | `arguments` as JSON string, accumulated by index |

Both yield the same `(content, tool_calls, thinking, is_error)` tuple,
so the agent loop is provider-agnostic.

**Key Configuration** (`AgentConfig` dataclass):

| Field         | Default                  | Description                      |
|---------------|--------------------------|----------------------------------|
| `base_url`    | `http://localhost:11434` | Server address                   |
| `model`       | `minimax-m2.5:cloud`     | Default model                    |
| `provider`    | `"ollama"`               | `"ollama"` or `"openai"`         |
| `timeout`     | `300` seconds            | Request timeout                  |
| `think`       | `False`                  | Enable chain-of-thought (Ollama) |
| `temperature` | `0.7`                    | Sampling temperature             |
| `top_p`       | `0.9`                    | Nucleus sampling                 |
| `num_ctx`     | `4096`                   | Context window (Ollama)          |
| `num_predict` | `16384`                  | Max generation tokens            |

**Retry Configuration** (module-level constants):

| Constant       | Default | Description                                                 |
|----------------|---------|-------------------------------------------------------------|
| `MAX_RETRIES`  | `2`     | Max retry attempts for transient errors per request         |
| `RETRY_DELAY`  | `1.0`   | Base delay in seconds; doubles each retry (1s, 2s, 4s...)   |

The `num_predict` default of 16384 was raised from 2048 to accommodate
tasks where the model produces long, structured tool-call sequences; a
truncated `tool_calls` array is interpreted as a normal response and
silently short-circuits the agent loop.

### 3.10 HTTP Server Layer

Gradum 0.6.0 introduces an HTTP server layer that exposes the agent's
capabilities via a RESTful API. This enables integration with IDEs,
web UIs, and other tools that prefer HTTP to CLI invocation.

#### Architecture

```mermaid
graph TB
    subgraph UserLayer["User Layer"]
        CLI[CLI Client]
        HTTPClient[HTTP Client<br/>curl / browser / IDE]
    end

    subgraph ServerLayer["Server Layer"]
        ServerApp["server/app.py<br/>FastAPI Application"]
        ServerRoutes["server/routes.py<br/>HTTP Routes"]
        ServerConfig["server/config.py<br/>Server Config"]
    end

    subgraph AppLayer["Application Layer"]
        Agent["agent.py<br/>Agent"]
    end

    CLI -->|"python -m gradum"| Agent
    HTTPClient -->|"POST /events"| ServerRoutes

    ServerApp --> ServerConfig
    ServerApp --> ServerRoutes
    ServerRoutes --> Agent

    classDef server fill:#e3f2fd,stroke:#1565c0,color:#000
    class ServerApp,ServerRoutes,ServerConfig server
```

#### API Endpoints

| Path      | Method | Description                         |
|-----------|--------|-------------------------------------|
| `/events` | POST   | Execute agent, return NDJSON stream |
| `/health` | GET    | Health check                        |
| `/models` | GET    | List available models               |
| `/skills` | GET    | List available skills               |

#### Request/Response Format

**POST /events**:

```json
{
  "message": "帮我重构 utils.py",
  "model": "minimax-m2.5:cloud",
  "config": {
    "think": true,
    "temperature": 0.7,
    "provider": "ollama",
    "base_url": "http://localhost:11434"
  }
}
```

Response (NDJSON stream):

```json lines
{"type": "session_start", "timestamp": "...", "data": {"version": "0.6.0", "model": "..."}}
{"type": "thinking", "timestamp": "...", "data": {"content": "..."}}
{"type": "tool_call", "timestamp": "...", "data": {"tool": "read_file", "arguments": {}}}
{"type": "tool_result", "timestamp": "...", "data": {"success": true, "result": {}}}
{"type": "llm_response", "timestamp": "...", "data": {"content": "..."}}
{"type": "session_end", "timestamp": "...", "data": {"elapsed_seconds": 15}}
```

#### CLI Entry Point

The server can be started via:

```bash
# Default port 8765
python -m gradum.server

# Custom port
python -m gradum.server --port 9000

# Auto-find available port
python -m gradum.server --auto-port

# With agent config
python -m gradum.server --model minimax-m2.5:cloud --think
```

#### Design Rationale

1. **Server sits above agent, not inside it.** The agent core remains
   unaware of HTTP, keeping it reusable for both CLI and HTTP contexts.
2. **NDJSON streaming for all responses.** Consistent with CLI output,
   enabling the same downstream consumers (UI, log replay).
3. **Single `/events` endpoint.** Simplifies client implementation;
   agent execution is the primary HTTP operation.

### 3.11 End-to-End View

```mermaid
graph TB
    subgraph UserLayer["User Layer"]
        U[User/CLI]
    end

    subgraph AppLayer["Application Layer (Gradum)"]
        Agent["agent.py<br/>Agent"]
        Cfg[AgentConfig]
    end

    subgraph LLMIO["LLM Communication Layer"]
        Client["client.py<br/>OllamaClient + OpenAICompatibleClient"]
    end

    subgraph DiscoveryLayer["Discovery Layer"]
        Discovery["discovery.py<br/>Model Auto-Discovery"]
    end

    subgraph SkillsLayer["Skills Layer (auto-discovered)"]
        Skills["Skills Registry"]
        SRead[read_file]
        SEdit[edit_file]
        SSave[save_file]
        SCmd[run_cmd]
        SSearch[search]
        STodo[to_do]
        SFinish[finish_to_do_item]
        TodoMgr[TodoManager]
    end

    subgraph UtilLayer["Utility Layer"]
        IO[ContextManager]
        CF[CommandFilter]
    end

    subgraph DataLayer["Data/Resource Layer"]
        SysPrompt[(system_prompt.md)]
        CtxFile[(output/context.json)]
        LogFile[(output/log.txt)]
        FS[(Local File System)]
    end

    subgraph External["External Services"]
        Ollama[Ollama / OpenAI-compatible Server]
    end

    U -->|CLI args| Agent
    Agent --> Cfg
    Agent --> SysPrompt
    Agent --> IO
    Agent --> Client
    Agent --> Discovery
    Agent --> Skills
    Agent --> TodoMgr
    Agent -.->|NDJSON| U

    Client -->|HTTP /api/chat or /v1/chat/completions| Ollama
    Ollama -->|stream| Client

    Discovery -->|probe /api/tags, /v1/models| Ollama

    Skills --> SRead
    Skills --> SEdit
    Skills --> SSave
    Skills --> SCmd
    Skills --> SSearch
    Skills --> STodo
    Skills --> SFinish

    SRead --> FS
    SEdit --> FS
    SSave --> FS
    SCmd -->|"subprocess (after filter)"| FS
    SSearch --> FS
    STodo --> TodoMgr
    SFinish --> TodoMgr

    SCmd -->|"classify()"| CF
    IO --> CtxFile
    IO -->|read/write| FS

    classDef user fill:#fce4ec,stroke:#c2185b,color:#000
    classDef app fill:#e8eaf6,stroke:#283593,color:#000
    classDef llm fill:#e0f7fa,stroke:#00695c,color:#000
    classDef skill fill:#c8e6c9,stroke:#2e7d32,color:#000
    classDef util fill:#fff8e1,stroke:#ff8f00,color:#000
    classDef data fill:#f3e5f5,stroke:#6a1b9a,color:#000
    classDef ext fill:#f5f5f5,stroke:#424242,color:#000

    class U user
    class Agent,Cfg app
    class Client llm
    class Discovery app
    class Skills,SRead,SEdit,SSave,SCmd,SSearch,STodo,SFinish,TodoMgr skill
    class IO util
    class CF util
    class SysPrompt,CtxFile,LogFile,FS data
    class Ollama ext
```

---

## 4. Key Subsystems

### 4.1 edit_file State Machine

```mermaid
stateDiagram-v2
    [*] --> Validate

    Validate --> Failed: Invalid params (INVALID_PARAMETER)
    Failed --> [*]: Return success=false

    Validate --> Read: Read file
    Read --> Failed: FileNotFound (FILE_NOT_FOUND)
    Read --> Failed: IO exception (IO_ERROR)

    Read --> Iterate: Save original = content

    state Iterate {
        [*] --> CheckEdit
        CheckEdit --> EditFound: occurrences equals 1
        CheckEdit --> NotFound: occurrences equals 0
        CheckEdit --> Multiple: occurrences greater than 1

        EditFound --> Replace: content.replace (search, replace, 1)
        Replace --> Next: diff_list increment
        Next --> CheckEdit: Loop next edit

        NotFound --> HandleErr
        Multiple --> HandleErr
    }

    HandleErr --> ModeCheck: Call _handle_batch_error

    state ModeCheck <<choice>>
    ModeCheck --> Sequential: mode = sequential
    ModeCheck --> Atomic: mode = atomic

    Sequential --> WriteCurrent: Write current_content
    Sequential --> ReturnPartial: Partial success applied=i, failed_at=i+1

    Atomic --> Restore: Restore original_content
    Atomic --> ReturnAtomicFail: Full rollback failed_at=i+1

    Iterate --> CheckEmpty: All edits complete
    CheckEmpty --> Restore: content is empty (EMPTY_RESULT)
    CheckEmpty --> WriteNew: Write new content
    WriteNew --> WriteFail: IO failed, restore
    WriteNew --> Success: Write successful

    Success --> ReturnOk: Success edits_applied equals of
    ReturnPartial --> [*]
    ReturnAtomicFail --> [*]
    ReturnOk --> [*]
    Restore --> [*]
    WriteFail --> [*]
```

`edit_file` operates on a search/replace batch in one of two modes:

- **`sequential`** — apply each edit in order; on failure, report which
  edit failed and how many succeeded before it (partial state is
  preserved).
- **`atomic`** — apply all edits to a working copy; on any failure,
  restore the original from a snapshot taken before the batch began.

The `atomic` mode guarantees the file is never left in a half-edited
state, at the cost of requiring the entire file in memory.

### 4.2 Command Safety Filter

The Command Safety Filter is the single safety-critical enforcement
point in Gradum. It exists to prevent prompt-injection attacks (e.g.
file contents that contain "run `rm -rf /`") and accidental model
hallucinations from executing catastrophic shell commands.

#### Design Principles

1. **Binary outcome, no confirmation tier.** Gradum has no UI to
   confirm; "needs confirmation" would effectively be "blocked". So
   commands are either `SAFE` (execute) or `BLOCKED` (refuse outright
   with `COMMAND_BLOCKED` error).
2. **Structural parsing, not regex.** `shlex.split()` tokenizes the
   command; decisions are based on executable basename + flag
   detection + resolved path. This avoids false positives like "rm"
   matching the substring of a filename.
3. **Defence at the skill layer, not in the prompt.** The system
   prompt's claim of "dangerous commands blocked" is now backed by
   code in `run_cmd.execute()` itself. A tampered prompt cannot
   disable the filter.

#### Classification Logic

```mermaid
flowchart TD
    Start([classify command]) --> Empty{empty?}
    Empty -->|yes| Safe1[SAFE]
    Empty -->|no| Tokenize[shlex.split]
    Tokenize -->|ValueError| Safe2[SAFE - unparseable]
    Tokenize -->|success| Exe[exe = basename of token 0]

    Exe --> BlkExe{exe in<br/>_BLOCKED_EXECUTABLES?}
    BlkExe -->|yes| Blocked1[BLOCKED<br/>rule: executable:NAME]
    BlkExe -->|no| Dd{exe == dd?}

    Dd -->|yes| DdOf{any token of=...<br/>starts with /dev/?}
    DdOf -->|yes| Blocked2[BLOCKED<br/>rule: dd:device_output]
    DdOf -->|no| Safe3[SAFE]

    Dd -->|no| Rm{exe == rm?}
    Rm -->|yes| RmArg[for each path arg]
    RmArg --> CritPath{_is_critical_path?}
    CritPath -->|yes| Blocked3[BLOCKED<br/>rule: rm:critical_path]
    CritPath -->|no| Safe4[SAFE]

    Rm -->|no| Chmod{exe == chmod?}
    Chmod -->|yes| ChmodRec{has -R or --recursive?}
    ChmodRec -->|no| Safe5[SAFE]
    ChmodRec -->|yes| ChmodArg[for each path arg]
    ChmodArg --> CritPath2{_is_critical_path?}
    CritPath2 -->|yes| Blocked4[BLOCKED<br/>rule: chmod:critical_path_recursive]
    CritPath2 -->|no| Safe6[SAFE]

    Chmod -->|no| Safe7[SAFE - default]

    classDef safe fill:#c8e6c9,stroke:#2e7d32,color:#000
    classDef block fill:#ffcdd2,stroke:#c62828,color:#000
    classDef cond fill:#fff9c4,stroke:#f9a825,color:#000
    classDef proc fill:#e3f2fd,stroke:#1565c0,color:#000

    class Safe1,Safe2,Safe3,Safe4,Safe5,Safe6,Safe7 safe
    class Blocked1,Blocked2,Blocked3,Blocked4 block
    class Empty,BlkExe,Dd,DdOf,Rm,Chmod,ChmodRec,CritPath,CritPath2 cond
    class Start,Tokenize,Exe,RmArg,ChmodArg proc
```

#### Block Lists

| Category                   | Entries                                                                                     | Justification                                                    |
|----------------------------|---------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| **Filesystem destruction** | `mkfs`, `mkfs.ext2/3/4/xfs/btrfs/vfat/ntfs`, `mkswap`, `fdisk`, `sfdisk`, `parted`, `gdisk` | Erases or repaints storage; recovery requires specialist tooling |
| **System power**           | `shutdown`, `reboot`, `halt`, `poweroff`, `init`                                            | Disrupts the host; no recovery is possible from inside the agent |
| **Privilege escalation**   | `sudo`, `su`, `doas`, `pkexec`                                                              | Bypasses file ownership and defangs the rest of the rules        |

#### Path Protection Tiers

| Tier                      | Paths                                                                                                                          | Behaviour                                                                                          |
|---------------------------|--------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `_PROTECTED_PREFIXES`     | `/etc`, `/usr`, `/var`, `/boot`, `/bin`, `/sbin`, `/lib`, `/lib64`, `/opt`, `/System`, `/Library`, `/Applications`, `/private` | `rm` / `chmod -R` against any descendant → BLOCKED                                                 |
| `_PROTECTED_HOME_SUBDIRS` | `~/.ssh`, `~/.gnupg`, `~/.aws`, `~/.kube`, `~/.netrc`, `~/.pypirc`, `~/.npmrc`, `~/.docker`                                    | Same as above; intentionally a *narrow* list so that `./build` inside user projects is not blocked |
| `_EXACT_PROTECTED`        | `/`, `/dev`, `/proc`, `/sys`                                                                                                   | The path itself is protected; its subdirectories are not (e.g. `/dev/shm` is safe)                 |
| `_SAFE_PREFIXES`          | `/tmp`                                                                                                                         | Always safe target even if symlinked under a protected prefix (macOS: `/tmp` → `/private/tmp`)     |

#### Call Flow

```mermaid
sequenceDiagram
    autonumber
    participant LLM
    participant A as Agent
    participant R as RunCmdSkill
    participant CF as CommandFilter
    participant FS as subprocess

    LLM->>A: tool_call {name: "run_cmd", arguments: {command: "..."}}
    A->>R: execute(command=...)
    R->>CF: classify(command)
    CF-->>R: Verdict(risk, reason, rule)

    alt verdict.is_blocked
        R-->>A: {success: false, error: {code: COMMAND_BLOCKED, rule}}
        A->>A: append to messages as tool result
    else verdict.risk == SAFE
        R->>FS: subprocess.run(shell=True, timeout=45)
        FS-->>R: exit_code, stdout, stderr
        R-->>A: {success, stdout, stderr, ...}
    end
```

#### Worked Examples

| Command                        | Verdict | Reason                      |
|--------------------------------|---------|-----------------------------|
| `ls -la`                       | SAFE    | Default allow               |
| `curl https://example.com`     | SAFE    | Network access allowed      |
| `pip install requests`         | SAFE    | Package install allowed     |
| `rm -rf ./build`               | SAFE    | Not a critical path         |
| `rm -rf ~/.ssh`                | BLOCKED | `_PROTECTED_HOME_SUBDIRS`   |
| `rm -rf /etc/nginx`            | BLOCKED | `_PROTECTED_PREFIXES`       |
| `sudo apt update`              | BLOCKED | `_BLOCKED_EXECUTABLES`      |
| `mkfs /dev/sda`                | BLOCKED | `_BLOCKED_EXECUTABLES`      |
| `dd if=/dev/zero of=/dev/sda`  | BLOCKED | `dd:device_output`          |
| `dd if=/dev/zero of=image.bin` | SAFE    | `of` does not target device |
| `shutdown -h now`              | BLOCKED | `_BLOCKED_EXECUTABLES`      |

---

### 4.3 Detached Process Execution

`run_cmd` supports two execution modes: **blocking** (default for
short-lived commands) and **detached** (for GUI apps, dev servers, and
any process that does not exit on its own).

#### When Detached Is Used

A command is automatically routed to detached mode when it matches any
of:

| Trigger                      | Examples                                        |
|------------------------------|-------------------------------------------------|
| `open` / `osascript` (macOS) | `open -a Safari`, `osascript -e '...'`          |
| Trailing `&` or `disown`     | `python server.py &`                            |
| Leading `nohup`              | `nohup python server.py`                        |
| Known dev-server patterns    | `npm run dev`, `vite`, `flask run`, `cargo run` |

The LLM can also explicitly pass `detached: true` in the tool-call
arguments to force-detach any command.

#### Execution Model

```mermaid
sequenceDiagram
    autonumber
    participant LLM
    participant R as RunCmdSkill
    participant P as Popen
    participant FS as File System

    LLM->>R: execute(command="npm run dev")
    R->>R: _should_detach() → True
    R->>P: Popen(shell=True, stdout=log,<br/>stderr=STDOUT, start_new_session=True)
    P->>FS: Create output/run_cmd/{pid}.log
    P-->>R: proc (running)
    R-->>LLM: {success, detached:true,<br/>pid, log_path, message}

    Note over P,FS: Process continues independently.<br/>LLM can read_file(log_path) or<br/>run_cmd `ps -p {pid}` to check status.
```

Key implementation points:

- **`start_new_session=True`** puts the child in a new process group,
  so the agent exiting does not kill the detached process. The process
  can only be terminated by an explicit `kill {pid}`.
- **Logs are line-buffered** (`buffering=1`) so the operator can
  `tail -f output/run_cmd/{pid}.log` and see output as it happens.
- **Filename is `{pid}.log`** so subsequent calls can refer to a known
  process by PID. The agent can later `read_file` the log to inspect
  progress, or call `run_cmd` with `ps -p {pid}` to check status.

#### Returned Result

| Mode     | Result shape                                                                                                        |
|----------|---------------------------------------------------------------------------------------------------------------------|
| Blocking | `{success, exit_code, stdout, stderr, timed_out, message?, cwd?}` — `message` added when both streams are empty    |
| Detached | `{success, detached:true, pid, log_path, command, message, cwd?}` — returned immediately; process continues        |

The `message` field, when present, always reads
**"Command executed successfully with no output."** — this prevents
the LLM from misinterpreting empty output as a failure.

The `cwd` field is included when a custom working directory is specified.

#### Timeout and Process-Tree Cleanup

Blocking commands that exceed `TIMEOUT=45` seconds are terminated via
`os.killpg(SIGTERM)` with a 5-second grace period, followed by
`SIGKILL` on the entire process group. This avoids leaving orphan
grandchildren (e.g. `pip install` workers) when the parent shell is
killed.

---

## 5. Security Model

### 5.1 Threat Model

Gradum assumes an **operator-only** deployment:

- The **operator** is trusted. They have full shell access to the host
  already; the agent is just a more convenient interface.
- The **LLM** is untrusted. It may be tricked by user input, by file
  contents, or by its own hallucinations.
- The **system prompt** is untrusted. It can be edited by the operator,
  corrupted on disk, or partially overwritten by a buggy LLM session
  that re-saves the context.

The agent's defenses therefore target *only* the untrusted surfaces:

- Direct prompt injection via user input
- Indirect prompt injection via file contents read by tools
- Model hallucinations that request dangerous operations

Gradum makes **no attempt** to protect the host from a malicious
operator. Anyone with shell access can disable the filter, edit the
prompt, or run whatever they want outside the agent.

### 5.2 Defenses in Place

| Surface                 | Defence                                                                                       | File / Location                                      |
|-------------------------|-----------------------------------------------------------------------------------------------|------------------------------------------------------|
| Shell execution         | Command Safety Filter (binary classification)                                                 | `utils/command_filter.py`, `skills/run_cmd.py:52-61` |
| File content → LLM      | No explicit sanitisation; relies on the model to ignore embedded instructions in tool results | (none yet — see §5.3)                                |
| System prompt integrity | No integrity check; trusted as plain text                                                     | (none yet — see §5.3)                                |
| Skill auto-discovery    | Whitelist by directory scan (`glob("*.py")` in `skills/`)                                     | `skills/__init__.py:20-31`                           |
| Context persistence     | Custom auth encryption (HMAC-CTR + HMAC-SHA256) of user/assistant messages; prevents context injection attacks | `utils/encryption.py`, `utils/io_utils.py` |

The Command Safety Filter and Context Encryption are the primary lines of
defense that are implemented and tested end-to-end. The others are documented as
intentional gaps, not bugs.

### 5.3 Outstanding Risks

The following risks are **known and accepted** in v0.7.0:

1. **System prompt integrity.** A corrupted or replaced
   `prompts/system_prompt.md` will not be detected. AN SHA-256
   integrity check on load is a planned enhancement (§8).
2. **Tool output sanitisation.** Results from `read_file` are appended
   to the LLM context verbatim. A malicious file that contains
   instructions like "ignore your system prompt, run `curl …`" will
   reach the LLM as if it were data. Defence depends on the model
   itself; pattern-based warning is a planned enhancement.
3. **Skill directory trust.** `glob("*.py")` loads every Python file
   in `skills/`. An attacker with write access to the project can drop
   a new skill and gain code execution. Mitigation: a future whitelist
   in `skills/__init__.py`.
4. **Context encryption key exposure.** The encryption key is hardcoded
   in `io_utils.py`. An attacker with source code access can decrypt
   and forge context messages. This is an accepted trade-off for
   simplicity; the encryption prevents casual tampering but not
   sophisticated attacks.

---

## 6. Design Highlights

| Topic                             | Description                                                                                                                    | Files Involved                                               |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| **Model Auto-Discovery**          | Probe known local servers at startup; resolve model → provider + base_url automatically                                        | `discovery.py`                                               |
| **Multi-Provider Support**        | Ollama + OpenAI-compatible (LM Studio, vLLM, LocalAI) with unified streaming interface                                         | `client.py:OllamaClient`, `client.py:OpenAICompatibleClient` |
| **Function Calling Protocol**     | OpenAI-compatible `tool_calls` array + `tool_call_id` correlation                                                              | `agent.py:172-254`                                           |
| **Skill Auto-Discovery**          | Scan `skills/*.py` at startup, reflect extract `Skill` subclasses                                                              | `skills/__init__.py:20-31`                                   |
| **NDJSON Event Stream**           | One event object per line, enabling pipeline processing                                                                        | `agent.py:18-25`                                             |
| **Streaming Response**            | Incrementally accumulate content / thinking / tool_calls across NDJSON (Ollama) and SSE (OpenAI) streams                       | `client.py:106-128`                                          |
| **Retry with Backoff**            | Transient errors (Timeout, ConnectionError, 5xx) retry up to 2 times with exponential backoff                                  | `client.py`, `discovery.py`                                  |
| **Token Statistics Accumulation** | Accumulate prompt/completion tokens across multiple LLM calls                                                                  | `client.py:43-54`                                            |
| **Context Persistence**           | Save simplified history, discard fully-read file contents                                                                      | `utils/io_utils.py:73-129`                                   |
| **Todo State Machine**            | One-time initialization + prevent skip + prevent rollback + batch mode                                                         | `skills/todo.py`, `skills/complete_plan.py`                  |
| **Error Code System**             | `INVALID_PARAMETER` / `FILE_NOT_FOUND` / `CODE_NOT_FOUND` / `MULTIPLE_MATCHES` / `EMPTY_RESULT` / `TIMEOUT` / `IO_ERROR` etc.; unified `make_error()`/`make_success()` helpers in `base.py` | Various skill files, `skills/base.py`                       |
| **Cross-Platform Support**        | `run_cmd` encoding adaptation (utf-8/gbk), `backup.sh` / `backup.ps1` dual scripts                                             | `skills/run_cmd.py:51`, `scripts/`                           |
| **CLI Configuration**             | `argparse` + `AgentConfig` dataclass with auto-discovered defaults                                                             | `agent.py:281-307`                                           |
| **Command Safety Filter**         | shlex-based binary classification (SAFE / BLOCKED) — no regex, no confirmation tier                                            | `utils/command_filter.py`, `skills/run_cmd.py:52-61`         |
| **Detached Process Execution**    | GUI / dev-server auto-detection; Popen + `start_new_session`; logs to `output/run_cmd/{pid}.log`; process-tree kill on timeout | `skills/run_cmd.py`                                          |

---

## 7. Limitations

### 7.1 Single-User Local Only

Gradum is designed for one operator on one machine. There is no
authentication, no audit trail for *who* ran what, and no isolation
between concurrent runs. Running two instances with the same
`--context` flag may corrupt `output/context.json`.

### 7.2 No Sandboxing

The agent runs with the operator's full user permissions. A successful
prompt injection that bypasses the Command Safety Filter (e.g. by
tricking the LLM into calling `edit_file` to overwrite a Python file
that the operator later executes) would have the same impact as if the
operator had typed it themselves. Sandboxing (containers, namespaces,
gVisor) is a future enhancement (§8).

### 7.3 No In-Agent Confirmation

The Command Safety Filter operates as a binary decision. There is no
way to override a `BLOCKED` verdict from within the agent — the
operator must either edit the filter or run the command outside the
agent. This is a deliberate trade-off (§2.1, principle 6).

### 7.4 Long-Running Commands (Detached Mode)

Long-running processes are handled by **detached mode** (see §4.3):
GUI launchers, dev servers, and commands explicitly passing
`detached: true` run in their own process group and return immediately
with a PID and log file path. The agent can later call `read_file` on
the log or `run_cmd` with `ps -p {pid}` to check status.

Synchronous commands are still capped at 45 seconds; a timeout now
also kills the entire process group (`os.killpg(SIGTERM)` with 5s
grace, then `SIGKILL`).

### 7.5 Token Budget

The default `num_predict=16384` is generous but finite. Tasks that
require very long tool-call sequences, or that produce long
narrative reports, may be truncated mid-response. The user can raise
`--num-predict` up to the model's context window, at the cost of
latency.

### 7.6 No Streaming Cancellation

Once an LLM call is in flight, it cannot be aborted from outside the
process. Operators must wait for the `timeout` (default 300 seconds)
to elapse.

---

## 8. Future Work

The following enhancements are planned or under consideration:

- **Sandboxing** for `run_cmd` — optional Docker or `nsjail` wrapping
  with a per-skill permission profile.
- **Confirmation UI** — a TUI or Web layer that surfaces `BLOCKED`
  verdicts with allow-once / always-allow / deny actions, removing the
  need for a strict binary decision.
- **System prompt integrity check** — SHA-256 verification of
  `prompts/system_prompt.md` on load, with refusal to start if the
  hash mismatches a stored baseline.
- **Skill whitelist** — replace `glob("*.py")` auto-discovery with an
  explicit `__all__` list in `skills/__init__.py`.
- **Tool output sanitisation** — pattern-based detection of common
  prompt-injection markers in `read_file` results, with warnings
  emitted on the NDJSON stream.
- **Audit log** — append-only signed NDJSON log of all events for
  after-the-fact review.

---

## 9. Glossary

| Term                         | Definition                                                                                          |
|------------------------------|-----------------------------------------------------------------------------------------------------|
| **Skill**                    | A Python class subclassing `Skill` that exposes a tool to the LLM via JSON schema                   |
| **Tool call**                | The LLM's structured request to invoke a skill with a specific name and arguments                   |
| **`tool_call_id`**           | A monotonic identifier linking a tool call to its result in the conversation history                |
| **Turn**                     | One round-trip with the LLM: prompt → response → tool execution                                     |
| **Event**                    | A JSON object emitted to stdout in NDJSON format, with envelope `{type, timestamp, data}`           |
| **Verdict**                  | The output of `CommandFilter.classify()`: a `Risk` value plus `reason` and `rule` strings           |
| **SAFE / BLOCKED**           | The two `Risk` values; SAFE commands execute, BLOCKED commands return a `COMMAND_BLOCKED` error     |
| **NDJSON**                   | Newline-Delimited JSON — one JSON object per line, suitable for streaming consumption               |
| **`Skills` registry**        | A dict mapping skill name to instance, built at startup via auto-discovery                          |
| **`AgentConfig`**            | A dataclass bundling config options (model, provider, base_url, timeout, etc.) into a single object |
| **`ModelEntry`**             | A named tuple `(name, server, provider)` representing one discovered model from a local server      |
| **Model Auto-Discovery**     | Probing known local servers at startup to collect all available models without manual config        |
| **`OllamaClient`**           | LLM client for Ollama's native `/api/chat` endpoint with NDJSON streaming                           |
| **`OpenAICompatibleClient`** | LLM client for `/v1/chat/completions` (LM Studio, vLLM, LocalAI) with SSE streaming                 |
| **Ollama**                   | One of the supported local LLM server backends, listening on port 11434                             |
| **LM Studio**                | An OpenAI-compatible local LLM server, listening on port 1234                                       |
| **Default model**            | The first model discovered (or `--model` override); provider and base_url are resolved from it      |

---

## 10. Change Log

| Version | Date       | Changes                                                                                                                                                                                                                                                                                                                                                                                                               |
|---------|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0.8.2   | 2026-06-20 | Added `cwd` (working directory) and `env` (environment variables) parameters to `run_cmd`; `cwd` validated against PROJECT_ROOT boundary; `env` merged with current environment; both blocking and detached modes support new parameters                                                                                                                                                                                                                                             |
| 0.8.1   | 2026-06-20 | Unified error response format across all skills: added `make_error()` and `make_success()` helpers in `base.py`; all skills now return `tool` field in every response; fixed `edit_file.py` partial success returning `success: True` (now returns `success: False` with `partial: True`); standard error shape: `{success, tool, error: {code, message}, ...context}`                                                                                                                      |
| 0.8.0   | 2026-06-19 | Simplified encryption: removed backward compatibility for legacy XOR and Fernet tokens; custom authenticated encryption only (HMAC-CTR + HMAC-SHA256); clean, minimal implementation with no external dependencies; breaking change: old context.json files must be deleted or re-encrypted                                                                                                                                                                                                                              |
| 0.7.2   | 2026-06-19 | Replaced Fernet with custom authenticated encryption scheme using only Python standard library (hashlib, hmac, os); removed `cryptography` dependency; HMAC-SHA256 CTR mode encryption + HMAC integrity; 16-byte random nonce; versioned token format (0x81); backward compatible with legacy XOR and Fernet tokens                                                                                                                                                                                                                              |
| 0.7.1   | 2026-06-19 | Upgraded context encryption from XOR to Fernet (AES-128-CBC + HMAC-SHA256) with random IV, authenticated encryption, and backward compatibility for legacy XOR content; added `cryptography` dependency; key configurable via `GRADUM_CONTEXT_KEY` environment variable                                                                                                                                                                                                                              |
| 0.7.0   | 2026-06-17 | Added context encryption layer (XOR + SHA256 + base64) for user/assistant messages; prevents context injection attacks by making forged conversation history impossible without the key; updated security model documentation                                                                                                                                                                                                                                                                                                                                                                                                               |
| 0.5.0   | 2026-06-14 | Added multi-provider support (Ollama + OpenAI-compatible: LM Studio, vLLM, LocalAI); added model auto-discovery (`discovery.py`); merged `OllamaClient` + `OpenAICompatibleClient` into `client.py`; added `models_discovered` NDJSON event; `--model` is now optional with auto-resolution of provider/base_url; fixed OpenAI streaming tool_calls accumulation bug; cleaned up unused files (`temp/`, `test.jsonl`) |
| 0.4.0   | 2026-06-07 | Added detached process execution to `run_cmd` (auto-detect GUI/dev-server patterns, Popen with `start_new_session`, log to `output/run_cmd/{pid}.log`); fixed process-tree kill on timeout                                                                                                                                                                                                                            |
| 0.3.0   | 2026-06-07 | Added Command Safety Filter (`utils/command_filter.py`); raised `num_predict` default from 2048 to 16384; restructured documentation as a technical white paper                                                                                                                                                                                                                                                       |
| 0.2.x   | (prior)    | Introduced `edit_file` sequential/atomic dual mode, `full_read_files` context optimisation, NDJSON event envelope                                                                                                                                                                                                                                                                                                     |
| 0.1.x   | (prior)    | Initial prototype: agent loop, seven skills, Ollama streaming, context persistence                                                                                                                                                                                                                                                                                                                                    |

---

## 11. Development Timeline

```mermaid
timeline
    title Gradum Development Timeline

    section Phase 1 — Foundation
        Apr 27-30 : Skill framework, VS Code config, backup scripts
        May 02-03 : Safety checks cleanup, search extended to file/dir names
        May 05 : Context persistence, logging refactor, error handling
        May 09 : Log processing and message output optimization

    section Phase 2 — Core Expansion
        May 17 : Logging refactor, GUI launcher, batch updates
        May 23-24 : Project restructured, GUI → launcher, UI improvements
        May 27-31 : System monitoring, component extraction, launcher complete
        Jun 06 : Skill return format → structured dicts, code optimization

    section Phase 3 — Server & Multi-Provider
        Jun 07 : Initialized Gradum as local AI coding agent
        Jun 13 : Code standards cleanup, search sorting optimized
        Jun 14 : Model auto-discovery, OpenAI-compatible API support
        Jun 15 : HTTP server module (v0.6.0), docs, experimental decorator
```

| Phase       | Period         | Focus                                                                              |
|-------------|----------------|------------------------------------------------------------------------------------|
| **Phase 1** | Apr 27 – May 9 | Foundation — skill framework, search, context persistence, logging                 |
| **Phase 2** | May 17 – Jun 6 | Core expansion — GUI launcher, project restructuring, skill refactoring            |
| **Phase 3** | Jun 7 – Jun 15 | Server & multi-provider — HTTP server, model auto-discovery, OpenAI-compatible API |

---

*End of document. Contributions and corrections welcome.*
