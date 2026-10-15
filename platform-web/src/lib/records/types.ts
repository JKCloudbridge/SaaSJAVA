import type { components } from "@/lib/api/generated/schema";

/** What the record screens read from the API, named once. The shapes are the generated ones; nothing is redefined. */
export type ObjectForm = components["schemas"]["ObjectFormView"];
export type FormField = components["schemas"]["FormFieldView"];
export type FormRecordType = components["schemas"]["FormRecordTypeView"];
export type FormSection = components["schemas"]["FormSectionView"];
export type RecordView = components["schemas"]["RecordView"];
export type QueriedRecord = components["schemas"]["QueriedRecordView"];
export type ListView = components["schemas"]["ListViewView"];
export type ListViewDefinition = components["schemas"]["ListViewDefinition"];
export type QueryFilterNode = components["schemas"]["QueryFilterNode"];
export type QueryRequest = components["schemas"]["QueryRequest"];
export type BulkResult = components["schemas"]["BulkRecordResult"];
export type BulkResponse = components["schemas"]["BulkRecordsResponse"];
export type ImportJob = components["schemas"]["ImportJobView"];

/** The values of a record by field API name, as the API sends them. */
export type RecordValues = Record<string, unknown>;

/**
 * What a control holds while a person types: text for most types, a flag for a checkbox, a list of choices for a
 * multi-select. Turning it into what the API accepts is {@link valueFromInput} in `fieldValue.ts`.
 */
export type FieldInput = string | boolean | string[];

/** Problems of a save by place: a field API name, or `fields` / `recordType` for what is not about one field. */
export type Problems = Record<string, string[]>;
