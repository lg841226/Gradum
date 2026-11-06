// A switch is a button, not a checkbox: the row's label already names the
// setting, so the control only has to carry its own on/off state for assistive
// tech. The thumb is a plain span, so the stylesheet owns every pixel of the
// track and the knob.
export function createSwitch({label, checked = false, onChange} = {}) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "switch";
  button.setAttribute("role", "switch");
  button.setAttribute("aria-checked", String(Boolean(checked)));
  if (label) {
    button.setAttribute("aria-label", label);
  }

  const thumb = document.createElement("span");
  thumb.className = "switch-thumb";
  thumb.setAttribute("aria-hidden", "true");
  button.appendChild(thumb);

  button.addEventListener("click", () => {
    onChange?.(button.getAttribute("aria-checked") !== "true");
  });
  return button;
}

export function setSwitchValue(button, value) {
  button.setAttribute("aria-checked", String(Boolean(value)));
}
