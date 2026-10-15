/*
 * Example custom page for the local profile (Sprint 18, ADR-0127). It is an ordinary script written against the small
 * interface the platform gives every custom page, `window.platform`, and it shows what such a page can do:
 *
 *   - wait for the platform (platform.ready) and read who looks at the page,
 *   - call the data interface with the viewer's own permissions (platform.json),
 *   - draw with the design values the platform passes in (var(--color-...)),
 *   - ask the platform to open another place (platform.navigate).
 *
 * It can reach nothing else: it runs in a sandboxed frame, may only talk to this platform's API, and every call is decided
 * by what the person looking at it may do. The full description is docs/custom-pages.md.
 */
(function () {
  "use strict";

  var style = document.createElement("style");
  style.textContent = [
    "main{padding:var(--space-5)}",
    "h2{margin:0 0 var(--space-1);font-size:var(--font-size-lg)}",
    "p{margin:0 0 var(--space-4);color:var(--color-text-muted)}",
    "table{width:100%;border-collapse:collapse;font-size:var(--font-size-sm)}",
    "th,td{padding:var(--space-2) var(--space-3);text-align:left;border-bottom:var(--border-width) solid var(--color-border)}",
    "th{color:var(--color-text-muted);font-size:var(--font-size-xs);text-transform:uppercase;letter-spacing:.06em}",
    ".chip{display:inline-block;margin:0 var(--space-1) var(--space-1) 0;padding:var(--space-0-5) var(--space-2);",
    "border-radius:var(--radius-pill);background:var(--color-accent-soft);color:var(--color-accent);font-weight:600}",
    ".note{padding:var(--space-3);border-radius:var(--radius-sm);background:var(--color-surface-sunken)}",
    ".error{color:var(--color-danger);background:var(--color-danger-surface)}",
    "button{margin-top:var(--space-4);padding:var(--space-2) var(--space-3);border:var(--border-width) solid var(--color-border-strong);",
    "border-radius:var(--radius-sm);background:var(--color-surface);color:var(--color-text);font:inherit;cursor:pointer}",
    "button:hover{border-color:var(--color-accent);color:var(--color-accent)}"
  ].join("");
  document.head.appendChild(style);

  var main = document.createElement("main");
  main.setAttribute("aria-live", "polite");
  main.innerHTML = '<p class="note">Loading…</p>';
  document.body.appendChild(main);

  function text(tag, value, className) {
    var node = document.createElement(tag);
    node.textContent = value;
    if (className) {
      node.className = className;
    }
    return node;
  }

  function render(who, access) {
    main.textContent = "";
    main.appendChild(text("h2", "Hello, " + who.displayName));
    main.appendChild(text("p", "This page was written by the organization and runs with your own permissions. It can show you only what you may see."));
    if (access.everything) {
      main.appendChild(text("p", "You may do everything with every object.", "note"));
    } else if (access.objects.length === 0) {
      main.appendChild(text("p", "You have no permission on any object yet.", "note"));
    } else {
      var table = document.createElement("table");
      var head = document.createElement("tr");
      head.appendChild(text("th", "Object"));
      head.appendChild(text("th", "What you may do"));
      table.appendChild(head);
      access.objects.forEach(function (entry) {
        var row = document.createElement("tr");
        row.appendChild(text("td", entry.key));
        var actions = document.createElement("td");
        entry.actions.forEach(function (action) {
          actions.appendChild(text("span", action, "chip"));
        });
        row.appendChild(actions);
        table.appendChild(row);
      });
      main.appendChild(table);
    }
    var back = text("button", "Back to the start");
    back.type = "button";
    back.addEventListener("click", function () {
      platform.navigate("/");
    });
    main.appendChild(back);
  }

  platform.ready.then(function () {
    return Promise.all([platform.json("/auth/me"), platform.json("/data-access/mine")]);
  }).then(function (answers) {
    render(answers[0].data, answers[1].data);
  }).catch(function (error) {
    main.textContent = "";
    main.appendChild(text("p", "This page could not load your data: " + error.message, "note error"));
  });
}());
