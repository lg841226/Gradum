# Gradum

A local-first AI coding agent with function calling, powered by Ollama and OpenAI-compatible APIs.

## Features

- **Local-first**: Runs entirely on your machine with no cloud dependencies
- **Multi-provider support**: Ollama, LM Studio, vLLM, LocalAI
- **Function calling**: Exposes file system and shell tools to the LLM
- **Command safety filter**: Blocks catastrophic shell commands even when the model is tricked
- **Streaming responses**: NDJSON event stream for low perceived latency
- **Context persistence**: Session continuity across runs
- **HTTP API**: RESTful endpoints for IDE and web UI integration

## Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/gradum.git
cd gradum

# Install in development mode
pip install -e .

# Or install with dev dependencies
pip install -e ".[dev]"
```

## Quick Start

### CLI Mode

```bash
# Basic usage
python -m gradum "Help me refactor utils.py"

# Specify model
python -m gradum -m minimax-m2.5:cloud "Help me refactor utils.py"

# Enable thinking mode
python -m gradum -t "Help me refactor utils.py"

# Load previous context
python -m gradum -c "Continue the previous task"

# Show help
python -m gradum --help
```

### HTTP Server Mode

```bash
# Start server (default port 8765)
python -m gradum.server

# Custom port
python -m gradum.server --port 9000

# Auto-find available port
python -m gradum.server --auto-port

# With model config
python -m gradum.server --model minimax-m2.5:cloud --think

# Show help
python -m gradum.server --help
```

## HTTP API

Once the server is running, you can interact via HTTP:

### Endpoints

| Path                    | Method | Description                                         |
|-------------------------|--------|-----------------------------------------------------|
| `/action`               | POST   | Start conversation, inject input, or cancel session |
| `/sessions`             | GET    | List all sessions                                   |
| `/sessions/{id}`        | GET    | Get session details                                 |
| `/sessions/{id}/events` | GET    | Get session events (NDJSON stream)                  |
| `/health`               | GET    | Health check                                        |
| `/models`               | GET    | List available models                               |
| `/skills`               | GET    | List available skills                               |

### Examples

```bash
# Start a conversation
curl -X POST http://localhost:8765/action \
  -H "Content-Type: application/json" \
  -d '{"message": "Help me refactor utils.py"}'

# Health check
curl http://localhost:8765/health

# List models
curl http://localhost:8765/models

# List skills
curl http://localhost:8765/skills
```

### Request Format

```json
{
  "message": "Help me refactor utils.py",
  "model": "minimax-m2.5:cloud",
  "config": {
    "think": true,
    "temperature": 0.7
  }
}
```

### Response Format (NDJSON Stream)

```json lines
{"type": "session_start", "session_id": "sess_abc123", "model": "..."}
{"type": "thinking", "content": "..."}
{"type": "tool_call", "tool": "read_file", "arguments": {}}
{"type": "tool_result", "success": true, "content": "..."}
{"type": "llm_response", "content": "..."}
{"type": "session_end", "elapsed_seconds": 15}
```

## Available Skills

| Skill               | Description                                  |
|---------------------|----------------------------------------------|
| `read_file`         | Read file content (full or line range)       |
| `edit_file`         | Search-replace edit                          |
| `save_file`         | Write file                                   |
| `run_cmd`           | Execute shell command (blocking or detached) |
| `search`            | Search code content/filenames/directories    |
| `to_do`             | Initialize task list                         |
| `finish_to_do_item` | Mark task as complete                        |

## Configuration

### CLI Options

| Option            | Description                   |
|-------------------|-------------------------------|
| `-m`, `--model`   | Specify model name            |
| `-t`, `--think`   | Enable thinking mode          |
| `-c`, `--context` | Load previous context         |
| `--provider`      | LLM provider (ollama, openai) |
| `--base-url`      | LLM server URL                |

### Server Options

| Option         | Description                                   |
|----------------|-----------------------------------------------|
| `--host`       | Host to bind (default: localhost)             |
| `--port`       | Port to bind (default: 8765)                  |
| `--auto-port`  | Auto-find available port                      |
| `--port-range` | Port range for auto-port (default: 8765-8775) |
| `--debug`      | Enable debug mode                             |
| `--model`      | Default model                                 |
| `--think`      | Enable thinking mode by default               |
| `--provider`   | LLM provider                                  |
| `--base-url`   | LLM server URL                                |

### Environment Variables

| Variable      | Description                 |
|---------------|-----------------------------|
| `GRADUM_PORT` | Server port (default: 8765) |

## LLM Backend Setup

### Ollama

```bash
# Install Ollama
# See: https://ollama.ai

# Pull a model
ollama pull minimax-m2.5:cloud

# Start Ollama server (default: http://localhost:11434)
ollama serve
```

### LM Studio

```bash
# Install LM Studio
# See: https://lmstudio.ai

# Start local server (default: http://localhost:1234)
# In LM Studio, go to "Local Server" tab and start
```

## Development

```bash
# Run tests
pytest

# Lint code
ruff check src/

# Format code
ruff format src/

# Auto-fix lint issues
ruff check --fix src/
```

## Architecture

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed technical documentation.

## Security

Gradum includes a **Command Safety Filter** that examines every shell command before execution and blocks catastrophic operations (e.g., `rm -rf /`, `sudo`, `chmod 777`). This provides defense against prompt injection attacks.

However, Gradum runs on your local machine with full filesystem and shell access. Use caution when running on sensitive codebases.

## Limitations

- Single-user operation (no multi-tenancy)
- No sandboxed execution
- No in-agent confirmation UI
- Text-only input (no multi-modal)

## License

MIT License - see [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please read [CODING_STANDARDS.md](docs/CODING_STANDARDS.md) before submitting PRs.

## Acknowledgments

- Built on [Ollama](https://ollama.ai) and OpenAI-compatible APIs
- Inspired by local-first AI coding assistants