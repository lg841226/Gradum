import {fetchHealth} from "../data/healthApi.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";
import {refreshStatusMobile, registerStatusItem} from "./statusbar.js";

const POLL_MS = 5000;

export function mountMonitor({host}) {
  // Appended first so it sits left of the memory bar it shares a poll with.
  const online = document.createElement("span");
  online.className = "statusbar-item statusbar-online";
  online.hidden = true;
  const onlineCount = document.createElement("span");
  online.append(createIcon({name: "users"}), onlineCount);
  host.appendChild(online);

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
  let shownDevices = null;
  let shownExternal = null;
  let lastUsed = null;
  let lastMax = 0;
  let offline = false;

  const paintDevices = (devices, external) => {
    const count = typeof devices === "number" ? devices : null;
    const outside = typeof external === "number" ? external : 0;
    if (count === shownDevices && outside === shownExternal) {
      return;
    }
    shownDevices = count;
    shownExternal = outside;
    online.hidden = count === null;
    if (count !== null) {
      onlineCount.textContent = String(count);
      online.dataset.tooltip = copy.statusBar.onlineTip(count, outside);
    }
  };

  const paint = (used, max, cpu, cores, devices, external) => {
    offline = false;
    lastUsed = used;
    lastMax = max;
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
    paintDevices(devices, external);
    refreshStatusMobile();
  };

  const paintOffline = () => {
    if (offline) {
      return;
    }
    offline = true;
    text.textContent = "—";
    fill.style.width = "0%";
    item.dataset.tooltip = copy.statusBar.monitorOffline;
    paintDevices(null);
    refreshStatusMobile();
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
      paint(system.heapUsedMb, system.heapMaxMb, system.cpuLoad, system.availableProcessors, health.onlineDevices, health.onlineExternal);
    } catch (_error) {
      paintOffline();
    }
  }

  void tick();
  setInterval(() => void tick(), POLL_MS);

  registerStatusItem({
    id: "online",
    order: 3,
    compact: () => (shownDevices === null
      ? null
      : {icon: "users", text: String(shownDevices)}),
  });

  registerStatusItem({
    id: "memory",
    order: 4,
    compact: () => (offline || lastUsed === null || lastMax <= 0
      ? null
      : {
        icon: "overhead",
        text: `${Math.min(100, Math.round(lastUsed / lastMax * 100))}%`,
      }),
  });
}
