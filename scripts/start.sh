#!/bin/bash

#
# Copyright (c) 2026 Gradum team, some rights reserved.
# For licensing terms and conditions, see the MIT LICENSE file.
#
# start.sh  2026-06-30 15:27:23 Changed by gwy
#

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

readonly RED='\033[0;31m'
readonly NC='\033[0m'

readonly USAGE="Usage: ./start.sh [server|plugin|both] [options]
  --port PORT       Set server port
  --provider NAME   Set provider name
  --auto-port       Auto-assign available port
  --debug           Enable debug mode
  --debug-port PORT Debug port (default: 5005)"

# Defaults
COMMAND="both"
SERVER_PORT=""
PROVIDER=""
AUTO_PORT=false
DEBUG=false
DEBUG_PORT="5005"

die() {
    echo -e "${RED}Error: $*${NC}" >&2
    exit 1
}

parse_args() {
    if [[ $# -gt 0 ]]; then
        case "$1" in
            server|plugin|both) COMMAND="$1"; shift ;;
            -h|--help) echo "$USAGE"; exit 0 ;;
            *) die "Unknown command: $1" ;;
        esac
    fi

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --port) SERVER_PORT="$2"; shift 2 ;;
            --provider) PROVIDER="$2"; shift 2 ;;
            --auto-port) AUTO_PORT=true; shift ;;
            --debug) DEBUG=true; shift ;;
            --debug-port) DEBUG_PORT="$2"; shift 2 ;;
            -h|--help) echo "$USAGE"; exit 0 ;;
            *) die "Unknown option: $1" ;;
        esac
    done
}

build_server_args() {
    local args=()
    [[ -n "$SERVER_PORT" ]] && args+=(--port "$SERVER_PORT")
    [[ -n "$PROVIDER" ]] && args+=(--provider "$PROVIDER")
    [[ "$AUTO_PORT" == true ]] && args+=(--auto-port)
    echo "${args[*]:-}"
}

start_server() {
    local args
    args="$(build_server_args)"
    if [[ -n "$args" ]]; then
        ./gradlew run "$args" --continuous
    else
        ./gradlew run --continuous
    fi
}

start_plugin() {
    if [[ "$DEBUG" == true ]]; then
        ./gradlew :plugin:runIde -Dorg.gradle.jvmargs="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=$DEBUG_PORT"
    else
        ./gradlew :plugin:runIde
    fi
}

open_terminal() {
    local cmd="$1"
    osascript -e "
        tell application \"Terminal\"
            activate
            do script \"$cmd\"
        end tell
    "
}

launch_both() {
    local server_args
    server_args="$(build_server_args)"

    local server_cmd="cd ${SCRIPT_DIR} && echo '── Gradum Server ──' && ./gradlew run ${server_args} --continuous"
    local plugin_cmd="cd ${SCRIPT_DIR} && echo '── Gradum Plugin ──' && ./gradlew :plugin:runIde"

    if [[ "$DEBUG" == true ]]; then
        plugin_cmd="cd ${SCRIPT_DIR} && echo '── Gradum Plugin (Debug :${DEBUG_PORT}) ──' && ./gradlew :plugin:runIde -Dorg.gradle.jvmargs=\"-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=${DEBUG_PORT}\""
    fi

    open_terminal "$server_cmd"
    sleep 0.5
    open_terminal "$plugin_cmd"
}

main() {
    parse_args "$@"

    case "$COMMAND" in
        server) start_server ;;
        plugin) start_plugin ;;
        both) launch_both ;;
    esac
}

main "$@"
