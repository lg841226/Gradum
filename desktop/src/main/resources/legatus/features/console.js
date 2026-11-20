import {formatTime} from "../core/dom.js";

function createLine({message, tone, time}) {
  const line = document.createElement("div");
  line.className = "output-line";
  line.dataset.tone = tone || "neutral";

  const stamp = document.createElement("span");
  stamp.className = "output-time";
  stamp.textContent = time || formatTime();

  const text = document.createElement("span");
  text.className = "output-text";
  text.textContent = message;

  line.append(stamp, text);
  return line;
}

export function mountConsole(store, {body}) {
  const container = document.createElement("div");
  container.className = "output";
  body.appendChild(container);

  let rendered = 0;

  const render = (state) => {
    let appended = 0;
    for (let index = rendered; index < state.log.length; index++) {
      container.appendChild(createLine(state.log[index]));
      appended++;
    }

    rendered = state.log.length;
    if (appended > 0) {
      body.scrollTop = body.scrollHeight;
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {container};
}
