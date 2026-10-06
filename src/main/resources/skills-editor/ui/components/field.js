// A single-line text input used for inline renaming. Enter commits, Escape
// cancels, and blur commits so the field never stays open by accident.
export function createTextField({value = "", onCommit, onCancel} = {}) {
  const input = document.createElement("input");
  input.type = "text";
  input.className = "text-field";
  input.spellcheck = false;
  input.autocomplete = "off";
  input.value = value;

  let settled = false;

  const commit = () => {
    if (settled) {
      return;
    }
    settled = true;
    onCommit?.(input.value);
  };

  const cancel = () => {
    if (settled) {
      return;
    }
    settled = true;
    onCancel?.();
  };

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      input.blur();
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancel();
    }
  });
  input.addEventListener("blur", commit);
  input.addEventListener("click", (event) => event.stopPropagation());

  return input;
}
