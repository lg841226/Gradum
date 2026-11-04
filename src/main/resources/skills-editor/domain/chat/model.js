// Chat semantics with no DOM + no clock of its own.
//
// One streaming turn is a small state machine: the agent text, the thinking
// block, the pre-content status row + the working-process group. The model
// owns those facts and emits effects; features/chat/view.js projects the
// effects onto nodes. Nothing else may keep a second copy of "is the agent
// bubble open" or "is the group live".

function idleState() {
  return {
    phase: "idle",
    group: null,
    pending: false,
    thinking: null,
    agent: {open: false, text: ""},
  };
}

export function createChatModel() {
  let state = idleState();

  function closePending() {
    if (!state.pending) {
      return [];
    }
    state.pending = false;
    return [{type: "pending:clear"}];
  }

  function finishThinking(now) {
    if (!state.thinking || state.thinking.done) {
      return [];
    }
    state.thinking = {
      ...state.thinking,
      done: true,
      elapsed: Math.max(0, now - state.thinking.startedAt),
    };
    return [{type: "thinking:finish", elapsed: state.thinking.elapsed}];
  }

  function closeAgent(now) {
    const out = [...closePending(), ...finishThinking(now)];
    if (state.agent.open) {
      state.agent = {open: false, text: ""};
      out.push({type: "agent:close"});
    }
    return out;
  }

  function settleGroup() {
    if (!state.group || !state.group.live) {
      return [];
    }
    state.group = {...state.group, live: false};
    return [{type: "group:settle", collapse: !state.group.userToggled}];
  }

  function reset() {
    state = idleState();
    return [{type: "reset"}];
  }

  return {
    getState() {
      return state;
    },

    beginTurn(text) {
      state = {...idleState(), phase: "streaming", pending: true};
      return [
        {type: "user", text},
        {type: "pending:show"},
      ];
    },

    apply(frame, now = Date.now()) {
      const data = frame.data ?? {};

      switch (frame.type) {
        case "response": {
          const content = typeof data.content === "string" ? data.content : "";
          if (!content) {
            return [];
          }
          const out = [
            ...closePending(),
            ...finishThinking(now),
            ...settleGroup(),
          ];
          if (!state.agent.open) {
            state.agent = {open: true, text: ""};
            out.push({type: "agent:start"});
          }
          state.agent.text += content;
          out.push({type: "agent:update", text: state.agent.text});
          return out;
        }

        case "thinking": {
          const out = [...closePending()];
          if (state.agent.open) {
            state.agent = {open: false, text: ""};
            out.push({type: "agent:close"});
          }
          if (!state.thinking || state.thinking.done) {
            if (!state.group || !state.group.live) {
              state.group = {live: true, userToggled: false, tools: []};
              out.push({type: "group:start"});
            }
            state.thinking = {startedAt: now, text: "", done: false, capped: false};
            out.push({type: "thinking:start", startedAt: now});
          }
          state.thinking.text += typeof data.content === "string" ? data.content : "";
          if (!state.thinking.capped && state.thinking.text.trim().length > 600) {
            state.thinking.capped = true;
          }
          out.push({
            type: "thinking:update",
            text: state.thinking.text,
            capped: state.thinking.capped,
          });
          return out;
        }

        case "tool_call_start":
          return closeAgent(now);

        case "tool_call": {
          const out = [...closeAgent(now)];
          if (!state.group || !state.group.live) {
            state.group = {live: true, userToggled: false, tools: []};
            out.push({type: "group:start"});
          }
          state.group.tools.push(data);
          out.push({type: "tool:add", data});
          return out;
        }

        case "ask_interaction":
          return [...closeAgent(now), ...settleGroup(), {type: "ask", frame}];

        case "error": {
          const out = [...closeAgent(now)];
          if (data.scope === "turn") {
            out.push(...settleGroup());
            out.push({
              type: "error",
              text: data.message ?? JSON.stringify(data),
            });
          }
          return out;
        }

        case "session_end":
          return [...closeAgent(now), ...settleGroup()];

        case "playback_start":
          return reset();

        default:
          return [];
      }
    },

    finishTurn(now = Date.now()) {
      const out = [...closeAgent(now), ...settleGroup()];
      state = {...state, phase: "idle"};
      return out;
    },

    toggleGroup() {
      if (state.group) {
        state.group = {...state.group, userToggled: true};
      }
    },

    notice(text) {
      return [{type: "notice", text}];
    },

    loadHistory(bubbles, now = Date.now()) {
      state = idleState();
      const out = [{type: "reset"}];
      for (const bubble of Array.isArray(bubbles) ? bubbles : []) {
        if (bubble.role === "user") {
          out.push({type: "user", text: bubble.content ?? ""});
          continue;
        }
        const blocks = Array.isArray(bubble.blocks) ? bubble.blocks : [];
        const merged = [];

        for (const block of blocks) {
          const last = merged[merged.length - 1];
          if (block.type === "thinking" && last?.type === "thinking") {
            last.content = `${last.content}\n\n${block.content ?? ""}`;
            continue;
          }
          merged.push({...block});
        }
        const grouped = merged.some((block) =>
          block.type === "thinking" || block.type === "toolCall");
        if (grouped) {
          state.group = {live: true, userToggled: false, tools: []};
          out.push({type: "group:start"});
        }

        for (const block of merged) {
          if (block.type === "thinking") {
            const text = typeof block.content === "string" ? block.content : "";
            state.thinking = {startedAt: now, text, done: true, elapsed: 0};
            out.push(
              {type: "thinking:start", startedAt: now},
              {type: "thinking:update", text, capped: text.trim().length > 600},
              {type: "thinking:finish", elapsed: -1},
            );
          } else if (block.type === "toolCall") {
            const data = {
              alias: block.alias ?? block.toolCallId,
              success: block.success !== false,
              arguments: block.arguments,
              result: block.result,
            };
            state.group.tools.push(data);
            out.push({type: "tool:add", data});
          } else if (block.type === "response") {
            const text = typeof block.content === "string" ? block.content : "";
            state.agent = {open: true, text};
            out.push({type: "agent:start"}, {type: "agent:update", text});
            state.agent = {open: false, text: ""};
            out.push({type: "agent:close"});
          }
        }

        if (grouped) {
          state.group = {...state.group, live: false};
          out.push({type: "group:settle", collapse: true});
        }
      }
      return out;
    },

    reset,
  };
}
