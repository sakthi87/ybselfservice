const FLAG = "odp-demo";
const DATA = "odp-demo-data";

export const DEMO_USER = {
  id: "demo-user",
  userId: "demo.user",
  subjectId: "user:demo.user",
};

export function demoEnabled() {
  return sessionStorage.getItem(FLAG) === "1";
}

export function enterDemo(initialState) {
  sessionStorage.setItem(FLAG, "1");
  sessionStorage.setItem(DATA, JSON.stringify(initialState()));
  return DEMO_USER;
}

export function exitDemo() {
  sessionStorage.removeItem(FLAG);
  sessionStorage.removeItem(DATA);
}

export function readDemoState(initialState) {
  const raw = sessionStorage.getItem(DATA);
  if (!raw) {
    const state = initialState();
    sessionStorage.setItem(DATA, JSON.stringify(state));
    return state;
  }
  return JSON.parse(raw);
}

export function writeDemoState(state) {
  sessionStorage.setItem(DATA, JSON.stringify(state));
}
