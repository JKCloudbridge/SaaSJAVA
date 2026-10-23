"use client";

import { useState, type KeyboardEvent } from "react";

export interface PickableField {
  apiName: string;
  label: string;
}

/**
 * The two boxes of "Select fields to display" (UI3A, ADR-0194): the fields that can be shown at the left, the ones shown
 * at the right in the order they are drawn, and buttons that move a field across and up or down. Enter on a field moves it
 * to the other box, so the whole thing works from the keyboard. It changes the columns of the list only: which fields
 * exist for the person is the server's description of the object, and nothing here grants a right to read one.
 */
export function FieldPicker({
  available,
  selected,
  onSave,
  onCancel,
}: {
  available: readonly PickableField[];
  selected: readonly string[];
  onSave: (names: string[]) => void;
  onCancel: () => void;
}) {
  const known = new Map(available.map((field) => [field.apiName, field]));
  const [chosen, setChosen] = useState<string[]>(() => selected.filter((name) => known.has(name)));
  const [leftPick, setLeftPick] = useState<string[]>([]);
  const [rightPick, setRightPick] = useState<string[]>([]);
  const rest = available.filter((field) => !chosen.includes(field.apiName));

  const add = (names: string[]) => {
    setChosen((current) => [...current, ...names.filter((name) => !current.includes(name))]);
    setLeftPick([]);
  };
  const take = (names: string[]) => {
    setChosen((current) => current.filter((name) => !names.includes(name)));
    setRightPick([]);
  };
  const shift = (direction: -1 | 1) => {
    setChosen((current) => {
      const next = [...current];
      const indexes = rightPick.map((name) => next.indexOf(name)).sort((a, b) => direction * (b - a));
      for (const index of indexes) {
        const target = index + direction;
        if (index >= 0 && target >= 0 && target < next.length && !rightPick.includes(next[target] ?? "")) {
          const held = next[index];
          const other = next[target];
          if (held !== undefined && other !== undefined) {
            next[index] = other;
            next[target] = held;
          }
        }
      }
      return next;
    });
  };
  const onEnter = (event: KeyboardEvent<HTMLSelectElement>, move: (names: string[]) => void, picked: string[]) => {
    if (event.key === "Enter") {
      event.preventDefault();
      move(picked);
    }
  };
  const values = (select: HTMLSelectElement) => Array.from(select.selectedOptions).map((option) => option.value);

  return (
    <div className="field-picker" data-testid="field-picker">
      <div className="field-picker-box">
        <label htmlFor="fields-available">Available fields</label>
        <select
          id="fields-available"
          multiple
          size={8}
          value={leftPick}
          onChange={(event) => setLeftPick(values(event.target))}
          onKeyDown={(event) => onEnter(event, add, leftPick)}
        >
          {rest.map((field) => (
            <option key={field.apiName} value={field.apiName}>
              {field.label}
            </option>
          ))}
        </select>
      </div>
      <div className="field-picker-buttons">
        <button type="button" className="button button-secondary" onClick={() => add(leftPick)} disabled={leftPick.length === 0}>
          Add ›
        </button>
        <button type="button" className="button button-secondary" onClick={() => take(rightPick)} disabled={rightPick.length === 0}>
          ‹ Remove
        </button>
        <button type="button" className="button button-secondary" onClick={() => shift(-1)} disabled={rightPick.length === 0}>
          Move up
        </button>
        <button type="button" className="button button-secondary" onClick={() => shift(1)} disabled={rightPick.length === 0}>
          Move down
        </button>
      </div>
      <div className="field-picker-box">
        <label htmlFor="fields-selected">Selected fields</label>
        <select
          id="fields-selected"
          multiple
          size={8}
          value={rightPick}
          onChange={(event) => setRightPick(values(event.target))}
          onKeyDown={(event) => onEnter(event, take, rightPick)}
        >
          {chosen.map((name) => (
            <option key={name} value={name}>
              {known.get(name)?.label ?? name}
            </option>
          ))}
        </select>
      </div>
      <div className="filters-actions field-picker-actions">
        <button type="button" className="button" onClick={() => onSave(chosen)}>
          Apply
        </button>
        <button type="button" className="button button-secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  );
}
