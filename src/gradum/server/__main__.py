"""CLI entry point for Gradum Server."""

import argparse
import socket

import uvicorn

from gradum.server.app import create_app
from gradum.server.config import ServerConfig


def is_port_available(port: int, host: str = "localhost") -> bool:
    """Check if port is available."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind((host, port))
            return True
        except OSError:
            return False


def find_available_port(start: int, attempts: int = 10, host: str = "localhost") -> int:
    """Find an available port."""
    for port in range(start, start + attempts):
        if is_port_available(port, host):
            return port
    raise RuntimeError(f"No available port in range {start}-{start + attempts}")


def main():
    """CLI entry point."""
    parser = argparse.ArgumentParser(description="Gradum HTTP Server")
    parser.add_argument("--host", default="localhost", help="Host to bind")
    parser.add_argument("--port", type=int, default=8765, help="Port to bind")
    parser.add_argument("--auto-port", action="store_true", help="Auto find available port")
    parser.add_argument("--port-range", default="8765-8775", help="Port range for auto-port")
    parser.add_argument("--debug", action="store_true", help="Enable debug mode")

    # Agent config options
    parser.add_argument("--model", default=None, help="Default model")
    parser.add_argument("--think", action="store_true", help="Enable thinking mode")
    parser.add_argument("--provider", default="ollama", help="LLM provider")
    parser.add_argument("--base-url", default=None, help="LLM server URL")

    args = parser.parse_args()

    # Determine port
    if args.auto_port:
        start, end = map(int, args.port_range.split("-"))
        port = find_available_port(start, end - start, args.host)
        print(f"Using port {port}")
    else:
        port = args.port

    # Build config
    config = ServerConfig(
        host=args.host,
        port=port,
        debug=args.debug,
        model=args.model,
        think=args.think,
        provider=args.provider,
        base_url=args.base_url,
    )

    # Create app
    app = create_app(config)

    # Run server
    uvicorn.run(
        app,
        host=config.host,
        port=config.port,
        log_level="info" if not args.debug else "debug",
    )


if __name__ == "__main__":
    main()