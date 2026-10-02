"use client";

import type { components } from "@/lib/api/generated/schema";

type Ability = components["schemas"]["AbilityInfo"];

/**
 * The abilities the API lists, as check boxes. What is chosen is only a request: the API refuses an unknown ability and
 * decides whether the caller may change anything at all.
 */
export function AbilityPicker({
  abilities,
  selected,
  onChange,
  disabled,
  legend,
}: {
  abilities: Ability[];
  selected: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  legend: string;
}) {
  function toggle(key: string, on: boolean) {
    onChange(on ? [...selected, key].sort() : selected.filter((existing) => existing !== key));
  }
  return (
    <fieldset className="field">
      <legend>{legend}</legend>
      {abilities.map((ability) => (
        <label key={ability.key} className="check">
          <input
            type="checkbox"
            checked={selected.includes(ability.key)}
            disabled={disabled}
            onChange={(event) => toggle(ability.key, event.target.checked)}
          />{" "}
          {ability.name}
          <span className="hint"> — {ability.description}</span>
        </label>
      ))}
    </fieldset>
  );
}
