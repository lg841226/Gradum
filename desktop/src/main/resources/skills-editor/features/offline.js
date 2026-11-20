import {fetchHealth} from "../data/healthApi.js";
import {watchConnection} from "../data/http.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";

const RETRY_SECONDS = 5;
const MAX_RETRIES = 99;
const LOST_AT_KEY = "gradum.lostAt";
const MEND_MS = 320;
const HOLD_MS = 2000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function mountOffline({canvas}) {
  let active = false;
  let timer = null;
  let left = 0;
  let lostAt = "";
  let attempts = 0;
  let probing = false;
  let confirming = false;
  let parts = null;

  const probe = async () => {
    try {
      await fetchHealth();
      return true;
    } catch (failure) {
      return failure && failure.name === "HttpError";
    }
  };

  const clock = (ms) => {
    const now = new Date(ms);
    const pad = (value) => String(value).padStart(2, "0");
    return `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  };

  const buildPage = () => {
    const wire = () => {
      const line = document.createElement("div");
      line.className = "offline-wire";
      return line;
    };
    const page = document.createElement("div");
    page.className = "offline-page";

    const spinner = document.createElement("span");
    spinner.className = "offline-spinner";
    const count = document.createElement("span");
    count.className = "offline-count";
    const tick = document.createElement("span");
    tick.className = "offline-check";
    tick.append(createIcon({name: "check", size: 20}));
    const state = document.createElement("span");
    state.className = "offline-state";
    state.append(spinner, count, tick);
    const hero = document.createElement("div");
    hero.className = "offline-hero";
    hero.append(
      createIcon({name: "browser", size: 28}),
      wire(), state, wire(),
      createIcon({name: "webServer", size: 28}),
    );

    const title = document.createElement("span");
    title.className = "offline-title";
    const hint = document.createElement("span");
    hint.className = "offline-hint";
    hint.textContent = copy.offline.hint;
    parts = {page, hero, title, count, hint};

    page.append(title, hero, hint);
    return page;
  };

  const arm = () => {
    left = RETRY_SECONDS;
    paint();
  };

  const paint = () => {
    parts.title.textContent = copy.offline.title(lostAt);
    parts.count.textContent = String(attempts);
    parts.page.setAttribute("aria-label", parts.title.textContent);
  };

  const tick = () => {
    if (probing) {
      return;
    }
    left -= 1;
    if (left > 0) {
      paint();
      return;
    }
    void check();
  };

  const check = async () => {
    if (probing) {
      return;
    }

    probing = true;
    const alive = await probe();
    if (alive) {
      parts.hero.classList.add("is-restored");
      await sleep(MEND_MS + HOLD_MS);
      sessionStorage.removeItem(LOST_AT_KEY);
      location.reload();
      return;
    }
    probing = false;
    attempts += 1;
    if (attempts >= MAX_RETRIES) {
      stop();
      return;
    }
    arm();
  };

  const stop = () => {
    clearInterval(timer);
    parts.hero.classList.add("is-given-up");
    parts.hint.textContent = copy.offline.givenUp;
    paint();
  };

  const enter = () => {
    if (active) {
      return;
    }
    active = true;
    let lostMs = Number(sessionStorage.getItem(LOST_AT_KEY));
    if (!Number.isFinite(lostMs) || lostMs <= 0) {
      lostMs = Date.now();
      sessionStorage.setItem(LOST_AT_KEY, String(lostMs));
    }
    attempts = 1;
    lostAt = clock(lostMs);
    canvas.replaceChildren(buildPage());
    document.documentElement.dataset.offline = "true";
    arm();
    timer = setInterval(tick, 1000);
  };

  const confirm = async () => {
    if (active || confirming) {
      return;
    }
    confirming = true;
    const alive = await probe();
    confirming = false;
    if (!alive) {
      enter();
    }
  };

  watchConnection((ok) => {
    if (ok) {
      return;
    }
    void confirm();
  });
}
