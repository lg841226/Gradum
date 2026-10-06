import {createIcon} from "./icon.js";

// variant: "ghost" | "icon"
export function createButton({
                               label,
                               icon,
                               variant = "ghost",
                               tooltip,
                               disabled = false,
                               onClick,
                               ariaLabel,
                             } = {}) {
  const button = document.createElement("button");
  button.type = "button";
  button.classList.add("btn");
  button.dataset.variant = variant;
  if (tooltip) button.title = tooltip;
  if (ariaLabel || (variant === "icon" && tooltip)) {
    button.setAttribute("aria-label", ariaLabel || tooltip);
  }
  if (icon) button.appendChild(createIcon({name: icon}));
  if (label) {
    const span = document.createElement("span");
    span.textContent = label;
    button.appendChild(span);
  }
  button.disabled = disabled;
  if (onClick) button.addEventListener("click", onClick);
  return button;
}

export function createIconButton({icon, tooltip, onClick, disabled = false, ariaLabel} = {}) {
  return createButton({icon, variant: "icon", tooltip, onClick, disabled, ariaLabel});
}
