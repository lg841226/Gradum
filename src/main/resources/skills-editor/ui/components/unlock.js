import {copy} from "../copy.js";
import {createIcon} from "./icon.js";

const LOOPBACK_HOSTNAME = /^(localhost|127(?:\.\d{1,3}){3}|\[::1]|::1)$/i;

const CODE_LENGTH = 5;

export function unlockMode() {
  return LOOPBACK_HOSTNAME.test(window.location.hostname) ? "token" : "code";
}

export function createUnlock({onToken, onCode} = {}) {
  const backdrop = document.createElement("div");
  backdrop.className = "unlock-backdrop";
  backdrop.hidden = true;

  const card = document.createElement("div");
  card.className = "unlock-card";
  card.setAttribute("role", "dialog");
  card.setAttribute("aria-modal", "true");
  card.setAttribute("aria-label", copy.unlock.title);

  const title = document.createElement("h2");
  title.className = "unlock-title";
  title.textContent = copy.unlock.title;

  // The blue info mark rides before the title.
  const titleRow = document.createElement("div");
  titleRow.className = "unlock-title-row";
  titleRow.append(createIcon({name: "info-outline", size: 20}), title);

  const hint = document.createElement("p");
  hint.className = "unlock-hint";

  // The token field: one masked input, kept for this machine's own console.
  const tokenInput = document.createElement("input");
  tokenInput.className = "unlock-input";
  tokenInput.type = "password";
  tokenInput.spellcheck = false;
  tokenInput.autocomplete = "off";
  tokenInput.placeholder = copy.unlock.placeholder;
  tokenInput.setAttribute("aria-label", copy.unlock.placeholder);

  // The pairing code: five single-character cells, one letter or digit each.
  const codeGroup = document.createElement("div");
  codeGroup.className = "unlock-codes";
  codeGroup.hidden = true;
  codeGroup.setAttribute("role", "group");
  codeGroup.setAttribute("aria-label", copy.unlock.codePlaceholder);

  const codeBoxes = [];
  for (let index = 0; index < CODE_LENGTH; index += 1) {
    const box = document.createElement("input");
    box.className = "unlock-code";
    box.type = "text";
    box.maxLength = 1;
    box.spellcheck = false;
    box.autocomplete = "off";
    box.setAttribute("autocapitalize", "characters");
    box.setAttribute("autocorrect", "off");
    box.setAttribute("aria-label", `Character ${index + 1} of ${CODE_LENGTH}`);
    codeBoxes.push(box);
    codeGroup.appendChild(box);
  }

  const error = document.createElement("p");
  error.className = "unlock-error";
  error.hidden = true;

  const actions = document.createElement("div");
  actions.className = "unlock-actions";

  const cancel = document.createElement("button");
  cancel.type = "button";
  cancel.className = "btn";
  cancel.dataset.variant = "ghost";
  cancel.textContent = copy.unlock.cancel;

  const submit = document.createElement("button");
  submit.type = "button";
  submit.className = "btn unlock-submit";
  submit.textContent = copy.unlock.submit;
  submit.disabled = true;

  actions.append(cancel, submit);
  card.append(titleRow, hint, tokenInput, codeGroup, error, actions);
  backdrop.appendChild(card);
  document.body.appendChild(backdrop);

  let mode = "token";

  const hide = () => {
    backdrop.hidden = true;
  };

  const codeValue = () => codeBoxes.map((box) => box.value).join("");
  const codeComplete = () => codeBoxes.every((box) => box.value.length === 1);
  const clearCode = () => {
    for (const box of codeBoxes) {
      box.value = "";
    }
  };

  const focusCodeBox = (index) => {
    const target = codeBoxes[Math.max(0, Math.min(CODE_LENGTH - 1, index))];
    target.focus();
    target.select();
  };

  const refreshSubmit = () => {
    submit.disabled =
      mode === "code" ? !codeComplete() : tokenInput.value.trim().length === 0;
  };

  // The red line belongs to the try that missed: the moment the user types
  // again, it is gone.
  const clearError = () => {
    error.textContent = "";
    error.hidden = true;
  };

  // Both modes share one field slot: the token is masked, the five
  // characters are visible (there is nothing secret about what you typed,
  // only about whether it is right).
  const applyMode = () => {
    const isCode = mode === "code";
    hint.textContent = isCode ? copy.unlock.codeHint : copy.unlock.hint;
    tokenInput.hidden = isCode;
    codeGroup.hidden = !isCode;
    refreshSubmit();
  };

  const show = (message, nextMode) => {
    mode = nextMode || unlockMode();
    applyMode();
    tokenInput.value = "";
    clearCode();
    error.textContent = message || "";
    error.hidden = !message;
    backdrop.hidden = false;
    if (mode === "code") {
      focusCodeBox(0);
    } else {
      tokenInput.focus();
    }
  };

  const submitValue = () => {
    if (mode === "code") {
      if (codeComplete()) {
        onCode?.(codeValue());
      }
      return;
    }
    const token = tokenInput.value.trim();
    if (token) {
      onToken?.(token);
    }
  };

  // A run of characters longer than one cell (a paste that slipped past the
  // paste handler, a drop) spreads forward from the cell it landed in. A full
  // row only arms the button: Unlock or Enter does the submitting.
  const distributeFrom = (start, chars) => {
    let written = 0;
    for (let index = start; index < CODE_LENGTH && written < chars.length; index += 1) {
      codeBoxes[index].value = chars[written];
      written += 1;
    }
    refreshSubmit();
    focusCodeBox(codeComplete() ? CODE_LENGTH - 1 : start + written);
  };

  tokenInput.addEventListener("input", () => {
    clearError();
    refreshSubmit();
  });
  tokenInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      submitValue();
    } else if (event.key === "Escape") {
      event.preventDefault();
      hide();
    }
  });

  for (const box of codeBoxes) {
    const index = codeBoxes.indexOf(box);

    box.addEventListener("focus", () => box.select());

    box.addEventListener("input", () => {
      clearError();
      const chars = box.value.replace(/[^a-zA-Z0-9]/g, "").toUpperCase();
      if (chars.length > 1) {
        distributeFrom(index, chars.slice(0, CODE_LENGTH - index));
        return;
      }
      box.value = chars;
      if (chars) {
        focusCodeBox(index + 1);
      }
      refreshSubmit();
    });

    box.addEventListener("paste", (event) => {
      event.preventDefault();
      const chars = (event.clipboardData?.getData("text") || "")
        .replace(/[^a-zA-Z0-9]/g, "")
        .toUpperCase();
      if (chars) {
        distributeFrom(index, chars);
      }
    });

    box.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        submitValue();
      } else if (event.key === "Backspace") {
        event.preventDefault();
        if (box.value) {
          box.value = "";
        } else if (index > 0) {
          codeBoxes[index - 1].value = "";
          focusCodeBox(index - 1);
        }
        refreshSubmit();
      } else if (event.key === "Delete") {
        event.preventDefault();
        box.value = "";
        refreshSubmit();
      } else if (event.key === "ArrowLeft") {
        event.preventDefault();
        focusCodeBox(index - 1);
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        focusCodeBox(index + 1);
      }
    });
  }

  cancel.addEventListener("click", hide);
  submit.addEventListener("click", submitValue);
  backdrop.addEventListener("pointerdown", (event) => {
    if (event.target === backdrop) {
      hide();
    }
  });
  backdrop.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      hide();
    }
  });

  return {show, hide};
}
