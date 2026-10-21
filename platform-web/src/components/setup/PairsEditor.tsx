"use client";

import { useState } from "react";
import { countPairs, FILTER_FROM, isChecked, setRow, toggled, visibleRows, type Pairs } from "@/lib/setup/pairs";

interface Option {
  value: string;
  label: string;
}

/**
 * The editor of a dependent picklist (FA2A, ADR-0177): choose the picklist this one depends on, then tick, for each
 * value of that picklist, the values of this one that go with it. A long grid gets a filter by controlling value. The
 * editor lists no rule of its own: the choices of the controlling field are the object's other single-value picklists,
 * and the API checks the result and answers in its own words.
 *
 * @param controllers the other single-value picklists of the object, with their values
 * @param locked whether the controlling field is the platform's to choose (a platform picklist whose list the
 *        organization owns): it is shown, not changed
 */
export function PairsEditor({
  controllers,
  controllingField,
  onControllingField,
  ownValues,
  pairs,
  onPairs,
  locked = false,
}: {
  controllers: Array<{ apiName: string; label: string; values: Option[] }>;
  controllingField: string;
  onControllingField: (apiName: string) => void;
  ownValues: Option[];
  pairs: Pairs;
  onPairs: (pairs: Pairs) => void;
  locked?: boolean;
}) {
  const [filter, setFilter] = useState("");
  const controller = controllers.find((candidate) => candidate.apiName === controllingField);
  const columns = ownValues.filter((value) => value.value !== "");
  const rows = visibleRows(controller?.values ?? [], filter);
  const listed = columns.map((column) => column.value);

  return (
    <fieldset className="field" data-testid="pairs-editor">
      <legend>Depends on</legend>
      {locked ? (
        <p>
          The choices go with the values of <strong>{controller?.label ?? controllingField}</strong>. The platform
          chose that field; the pairs below are yours.
        </p>
      ) : (
        <div className="field">
          <label htmlFor="field-controlling">The picklist these values depend on</label>
          <select
            id="field-controlling"
            value={controllingField}
            onChange={(event) => onControllingField(event.target.value)}
          >
            <option value="">Nothing: every value can always be chosen</option>
            {controllers.map((candidate) => (
              <option key={candidate.apiName} value={candidate.apiName}>
                {candidate.label}
              </option>
            ))}
          </select>
          <p className="hint">
            Once a value of the other picklist is chosen, only the values ticked for it can be chosen here.
          </p>
        </div>
      )}
      {controller ? (
        <>
          {controller.values.length >= FILTER_FROM ? (
            <div className="field">
              <label htmlFor="pairs-filter">Filter by {controller.label}</label>
              <input
                id="pairs-filter"
                type="search"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
            </div>
          ) : null}
          {columns.length === 0 ? (
            <p className="hint">Add values to this picklist first, then tick which go with which.</p>
          ) : (
            <div>
              <table className="table" aria-label="Which values go with which">
                <thead>
                  <tr>
                    <th scope="col">{controller.label}</th>
                    {columns.map((column) => (
                      <th scope="col" key={column.value}>
                        {column.label || column.value}
                      </th>
                    ))}
                    <th scope="col">
                      <span className="visually-hidden">All or none</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.value} data-testid="pairs-row">
                      <th scope="row">{row.label || row.value}</th>
                      {columns.map((column) => (
                        <td key={column.value}>
                          <input
                            type="checkbox"
                            aria-label={`${row.label || row.value}: ${column.label || column.value}`}
                            checked={isChecked(pairs, row.value, column.value)}
                            onChange={(event) =>
                              onPairs(toggled(pairs, row.value, column.value, event.target.checked))
                            }
                          />
                        </td>
                      ))}
                      <td>
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => onPairs(setRow(pairs, row.value, listed, true))}
                        >
                          All
                        </button>{" "}
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => onPairs(setRow(pairs, row.value, listed, false))}
                        >
                          None
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="hint" data-testid="pairs-count">
            {countPairs(pairs)} pairs ticked.
          </p>
        </>
      ) : null}
    </fieldset>
  );
}
