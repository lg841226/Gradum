import {fetchHealth} from "../data/healthApi.js";
import {copy} from "../ui/copy.js";

const POLL_MS = 5000;

export function mountMonitor({host}) {
  const item = document.createElement("span");
  item.className = "statusbar-item statusbar-monitor";

  const track = document.createElement("span");
  track.className = "monitor-track";
  const fill = document.createElement("span");
  fill.className = "monitor-fill";
  track.appendChild(fill);

  const text = document.createElement("span");
  item.append(track, text);
  host.appendChild(item);

  let shown = null;
  let offline = false;

  const paint = (used, max, cpu, cores) => {
    offline = false;
    const label = copy.statusBar.memory(used, max);
    if (label !== shown) {
      shown = label;
      text.textContent = label;
      fill.style.width = max > 0
        ? `${Math.min(100, Math.round(used / max * 100))}%`
        : "0%";
    }
    let tip = copy.statusBar.memoryTip(used, max);
    if (cpu !== null && cpu !== undefined) {
      tip += copy.statusBar.cpuTip(cpu, cores);
    }
    item.dataset.tooltip = tip;
  };

  const paintOffline = () => {
    if (offline) {
      return;
    }
    offline = true;
    text.textContent = "—";
    fill.style.width = "0%";
    item.dataset.tooltip = copy.statusBar.monitorOffline;
  };

  async function tick() {
    if (document.hidden) {
      return;
    }
    try {
      const health = await fetchHealth();
      const system = health && health.system;
      if (!system) {
        paintOffline();
        return;
      }
      paint(system.heapUsedMb, system.heapMaxMb, system.cpuLoad, system.availableProcessors);
    } catch (_error) {
      paintOffline();
    }
  }

  void tick();
  setInterval(() => void tick(), POLL_MS);
}
