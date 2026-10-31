import { describe, expect, it } from "vitest";
import {
  addField,
  changeField,
  designerDraftOf,
  mappingPlaceWords,
  moveField,
  moveFieldTo,
  newDesignerDraft,
  newField,
  removeField,
  sampleOf,
  specOf,
  unsaved,
  unusedFields,
  uniqueName,
  type MappingCatalogue,
  type MappingView,
  type ObjectField,
} from "./mappingModel";

const CATALOGUE: MappingCatalogue = {
  conflicts: [{ key: "rule-1", label: "Rule one", hint: "" }],
  directions: [{ key: "dir-1", label: "Direction one", hint: "" }],
  externalTypes: [{ key: "type-1", label: "Type one", hint: "" }],
  conversions: [{ key: "conv-1", label: "Conversion one", hint: "" }],
};

function objectField(apiName: string): ObjectField {
  return {
    apiName,
    label: apiName,
    type: "T",
    kind: "CUSTOM",
    description: "",
    editable: true,
    required: false,
    retired: false,
    unique: false,
    version: 0,
    settings: {},
  } as ObjectField;
}

describe("the mapping model", () => {
  it("starts a new draft from the first choice of the catalogue and nothing else", () => {
    const draft = newDesignerDraft(CATALOGUE);

    expect(draft).toMatchObject({ conflict: "rule-1", inboundCreate: false, inboundUpdate: false, fields: [], version: 0 });
  });

  it("adds a field with the catalogue's first choices and a name that is not taken", () => {
    const first = newField(objectField("name__c"), CATALOGUE, []);
    const second = newField(objectField("name__c"), CATALOGUE, [first.external]);

    expect(first).toMatchObject({
      external: "name",
      platform: "name__c",
      direction: "dir-1",
      externalType: "type-1",
      conversion: "conv-1",
    });
    expect(second.external).toBe("name_2");
    expect(uniqueName("1st", [])).toBe("f_1st");
  });

  it("changes, moves and removes fields as pure operations, and a move off the end changes nothing", () => {
    let draft = newDesignerDraft(CATALOGUE);
    for (const name of ["a__c", "b__c", "c__c"]) {
      draft = addField(draft, newField(objectField(name), CATALOGUE, draft.fields.map((one) => one.external)));
    }

    expect(moveField(draft, 0, -1)).toBe(draft);
    expect(moveField(draft, 2, 1)).toBe(draft);
    expect(moveField(draft, 0, 1).fields.map((one) => one.platform)).toEqual(["b__c", "a__c", "c__c"]);
    expect(moveFieldTo(draft, 2, 0).fields.map((one) => one.platform)).toEqual(["c__c", "a__c", "b__c"]);
    expect(moveFieldTo(draft, 1, 1)).toBe(draft);
    expect(changeField(draft, 1, { required: true }).fields[1]?.required).toBe(true);
    expect(draft.fields[1]?.required).toBe(false);
    expect(removeField(draft, 1).fields.map((one) => one.platform)).toEqual(["a__c", "c__c"]);
  });

  it("offers in the palette only the fields no map uses", () => {
    const draft = addField(newDesignerDraft(CATALOGUE), newField(objectField("a__c"), CATALOGUE, []));

    expect(unusedFields([objectField("a__c"), objectField("b__c")], draft).map((one) => one.apiName)).toEqual(["b__c"]);
  });

  it("sends empty text as nothing and keeps the order of the fields", () => {
    const draft = addField(
      { ...newDesignerDraft(CATALOGUE), changedAtField: "  " },
      { ...newField(objectField("a__c"), CATALOGUE, []), outExpression: " ", defaultValue: "x" },
    );

    const spec = specOf(draft);

    expect(spec.changedAtField).toBeUndefined();
    expect(spec.fields[0]).toMatchObject({ outExpression: undefined, defaultValue: "x", valueMap: [] });
  });

  it("starts from the saved draft, else the live version, and says what is unsaved", () => {
    const definition = specOf(addField(newDesignerDraft(CATALOGUE), newField(objectField("a__c"), CATALOGUE, [])));
    const live = { id: "1", number: 1, status: "LIVE", hash: "h", version: 1, definition };
    const view = { id: "m", connection: "c", object: "Order__c", entity: "order", live, history: [], canPublish: false } as unknown as MappingView;

    const draft = designerDraftOf(view, CATALOGUE);

    expect(draft.entity).toBe("order");
    expect(draft.fields).toHaveLength(1);
    expect(draft.version).toBe(0);
    expect(unsaved(draft, view, CATALOGUE)).toBe(false);
    expect(unsaved(changeField(draft, 0, { external: "other" }), view, CATALOGUE)).toBe(true);
  });

  it("names a place in the words of the screen", () => {
    expect(mappingPlaceWords("definition.fields[2].platform")).toBe("Field 3, platform");
    expect(mappingPlaceWords("definition.fields[0]")).toBe("Field 1");
    expect(mappingPlaceWords("definition.conflict")).toBe("conflict");
  });

  it("leaves blank entries out of a sample", () => {
    expect(sampleOf({ a: "1", b: "  ", c: "" })).toEqual({ a: "1" });
  });
});
