"use client";
// POC 1 (throwaway). Univer 1.4.0 under test. Query parameters:
//   fixture=plant|spm  sheet=<index>    a real workbook, fetched from /poc1-fixture/<name>.json (served by the browser script)
//   classes=<seed>  role=filler|reviewer  guard=none|builtin|events|both     the cell-class experiment on a synthetic sheet
//   full=1                              add the conditional-format and find-replace presets
// Publishes window.__poc for the browser script.
import { useEffect, useRef, useState } from "react";
import "@univerjs/preset-sheets-core/lib/index.css";
import { toUniverSheet, toUniverWorkbook, syntheticSheet } from "../adapters/poc1Univer.mjs";
import { layoutOf, forbiddenSet, ROWS, COLS } from "../lib/poc1Layout.mjs";

function params() {
  const q = new URLSearchParams(window.location.search);
  return {
    fixture: q.get("fixture"), sheet: Number(q.get("sheet") ?? 0), sheets: q.get("sheets"), classes: q.get("classes"), role: q.get("role") ?? "filler",
    guard: q.get("guard") ?? "none", full: q.get("full") === "1",
  };
}

export default function Poc1Univer() {
  const box = useRef(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let made;
    let cancelled = false;
    const t0 = performance.now();
    (async () => {
      try {
        const p = params();
        const imports = [import("@univerjs/presets"), import("@univerjs/preset-sheets-core"), import("@univerjs/preset-sheets-core/locales/en-US")];
        if (p.full) {
          imports.push(import("@univerjs/preset-sheets-conditional-formatting"), import("@univerjs/preset-sheets-conditional-formatting/locales/en-US"),
            import("@univerjs/preset-sheets-find-replace"), import("@univerjs/preset-sheets-find-replace/locales/en-US"));
          await Promise.all([import("@univerjs/preset-sheets-conditional-formatting/lib/index.css"), import("@univerjs/preset-sheets-find-replace/lib/index.css")]);
        }
        const mods = await Promise.all(imports);
        if (cancelled) return;
        const [{ createUniver, LocaleType, mergeLocales }, { UniverSheetsCorePreset }, core] = mods;
        const presets = [UniverSheetsCorePreset({ container: box.current })];
        const locales = [core.default];
        if (p.full) {
          presets.push(mods[3].UniverSheetsConditionalFormattingPreset(), mods[5].UniverSheetsFindReplacePreset());
          locales.push(mods[4].default, mods[6].default);
        }
        made = createUniver({ locale: LocaleType.EN_US, locales: { [LocaleType.EN_US]: mergeLocales(...locales) }, presets });
        const api = made.univerAPI;

        let data;
        let grid = null;
        if (p.classes) {
          grid = layoutOf(Number(p.classes));
          data = syntheticSheet(ROWS, COLS);
        } else {
          const neutral = await (await fetch(`/poc1-fixture/${p.fixture}.json`)).json();
          data = p.sheets ? toUniverWorkbook(neutral, p.sheets.split(",").map(Number)) : toUniverSheet(neutral, p.sheet);
        }
        const loadedAt = performance.now();
        api.createWorkbook(data);
        window.__poc = { api, loadedAt, startedAt: t0, grid, params: p };
        installGuards(api, p, grid);
        const t1 = performance.now();
        while (!box.current?.querySelector("canvas") && performance.now() - t1 < 20000) await new Promise((r) => setTimeout(r, 25));
        requestAnimationFrame(() => requestAnimationFrame(() => { window.__poc.readyAt = performance.now(); window.__poc.ready = true; }));
      } catch (e) {
        setError(String(e?.name ?? "Error"));
        window.__poc = { ...(window.__poc ?? {}), failed: String(e?.message ?? e).slice(0, 300) };
      }
    })();
    return () => { cancelled = true; try { made?.univer?.dispose?.(); } catch { /* ignore */ } };
  }, []);

  return (
    <main className="spike-page">
      {error && <p role="alert">Failed to start: {error}</p>}
      <div ref={box} style={{ height: "36rem", position: "relative" }} />
    </main>
  );
}

// ---- the guards under test ----

function installGuards(api, p, grid) {
  if (!grid || p.guard === "none") return;
  const forbidden = forbiddenSet(grid, p.role);
  const hit = (r, c) => forbidden.has(`${r},${c}`);
  const log = (window.__poc.blocked = []);

  if (p.guard === "events" || p.guard === "both") {
    // FILTER: nothing a person does can reach a forbidden cell, because the one lowest-level mutation that writes cell values is
    // rewritten before it runs (forbidden cells are removed from it); structural changes that touch a forbidden cell are cancelled.
    api.addEvent(api.Event.BeforeSheetEditStart, (e) => { if (hit(e.row, e.column)) { e.cancel = true; log.push("edit-start"); } });
    api.addEvent(api.Event.BeforeCommandExecute, (e) => {
      if (window.__poc.bypass) return;
      if (e.id === "sheet.mutation.set-range-values" && e.params?.cellValue) {
        let removed = 0;
        for (const r of Object.keys(e.params.cellValue)) {
          for (const c of Object.keys(e.params.cellValue[r] ?? {})) if (hit(Number(r), Number(c))) { delete e.params.cellValue[r][c]; removed++; }
          if (!Object.keys(e.params.cellValue[r] ?? {}).length) delete e.params.cellValue[r];
        }
        if (removed) log.push(`filtered ${removed}`);
      } else if (STRUCTURAL.test(e.id) && rangesOf(e.params).some((rg) => touches(rg, hit))) { e.cancel = true; log.push(e.id); }
    });
  }
  if (p.guard === "block") {
    // BLOCK: cancel the whole operation when it touches a forbidden cell (a paste can only be cancelled as a whole)
    api.addEvent(api.Event.BeforeSheetEditStart, (e) => { if (hit(e.row, e.column)) { e.cancel = true; log.push("edit-start"); } });
    api.addEvent(api.Event.BeforeClipboardPaste, (e) => {
      const sel = api.getActiveWorkbook().getActiveSheet().getSelection()?.getActiveRange?.();
      if (sel && touches(sel.getRange(), hit)) { e.cancel = true; log.push("paste"); }
    });
    api.addEvent(api.Event.BeforeCommandExecute, (e) => {
      if (window.__poc.bypass) return;
      const touchedCells = e.id === "sheet.mutation.set-range-values" && e.params?.cellValue
        && Object.entries(e.params.cellValue).some(([r, row]) => Object.keys(row ?? {}).some((c) => hit(Number(r), Number(c))));
      if (touchedCells || (STRUCTURAL.test(e.id) && rangesOf(e.params).some((rg) => touches(rg, hit)))) { e.cancel = true; log.push(e.id); }
    });
  }
  if (p.guard === "builtin" || p.guard === "both") {
    const sheet = api.getActiveWorkbook().getActiveSheet();
    const perm = sheet.getWorksheetPermission();
    window.__poc.builtin = (async () => {
      const configs = [];
      for (const key of forbidden) {
        const [r, c] = key.split(",").map(Number);
        configs.push({ ranges: [sheet.getRange(r, c, 1, 1)], options: { allowViewByOthers: true, allowedUsers: [] } });
      }
      try { await perm.protectRanges(configs); return "protected " + configs.length; } catch (err) { return "failed " + String(err?.message ?? err).slice(0, 80); }
    })();
  }
}

function touches(rg, hit) {
  if (!rg) return false;
  const r0 = rg.startRow, r1 = rg.endRow, c0 = rg.startColumn, c1 = rg.endColumn;
  if ([r0, r1, c0, c1].some((x) => typeof x !== "number")) return false;
  for (let r = r0; r <= Math.min(r1, ROWS + 20); r++) for (let c = c0; c <= Math.min(c1, COLS + 5); c++) if (hit(r, c)) return true;
  return false;
}

function rangesOf(params) {
  const out = [];
  if (!params) return out;
  if (params.range) out.push(params.range);
  if (params.ranges) out.push(...params.ranges);
  if (params.value && typeof params.value === "object") for (const [r, row] of Object.entries(params.value)) {
    if (typeof row === "object") for (const c of Object.keys(row)) out.push({ startRow: Number(r), endRow: Number(r), startColumn: Number(c), endColumn: Number(c) });
  }
  if (params.selection?.range) out.push(params.selection.range);
  if (params.selections) out.push(...params.selections);
  if (params.pastedRange) out.push(params.pastedRange);
  if (params.targetRange) out.push(params.targetRange);
  if (params.destRange) out.push(params.destRange);
  if (params.fromRange) out.push(params.fromRange);
  if (params.toRange) out.push(params.toRange);
  return out;
}

const STRUCTURAL = /^sheet\.(mutation|command)\.(move-range|remove-rows|remove-cols|remove-row-by-range|remove-col-by-range|delete-range-move|add-worksheet-merge|remove-worksheet-merge|insert-range-move)/;
const MUTATING = /^sheet\.(command|mutation)\.(set-range-values|clear-selection|move-range|auto-fill|paste|insert-row|insert-col|remove-row|remove-col|delete-range|add-worksheet-merge|remove-worksheet-merge|delete-worksheet-merge|replace|sheet-paste|set-style|set-range-)/;
function isMutating(id) { return MUTATING.test(id) || /paste|fill|clear|replace|merge|insert|remove|delete|move/i.test(id); }
