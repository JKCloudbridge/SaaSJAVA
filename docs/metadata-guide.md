# Metadata guide: objects, fields and how to change them

Written for anyone who has to add or change what the platform defines: a new standard object, a new field on one, a label, a picklist value, or a
new kind of field. You do not need to know databases for most of it. Decisions behind it: [ADR-0058](adr/0058-object-and-field-definitions.md) to
[ADR-0062](adr/0062-removing-objects-and-fields.md). The list of what exists today is generated: [standard-objects.md](standard-objects.md).

## 1. The ideas in plain words

- **Metadata** is the description of an organization's application: which kinds of things it keeps (objects), and what it knows about each (fields). It
  is data, not code, so an organization can change it without a new release of the platform.
- **Object**: a kind of record, for example Account, Employee or Vehicle. **Field**: one piece of information on an object, for example the name of an
  account or the salary of an employee.
- Every object and field has a **label** (what people read; can be changed any time) and an **API name** (permanent; what code, integrations and the
  address of a record use). Objects are written `Account`, `Employee__c`; fields `accountId`, `salary__c`.
- **Three kinds of definition.** *Standard* (defined by the platform, the same for every organization, protected), *custom* (made by an organization
  for itself, always ending in `__c`) and *system* (the eight fields every object has). The ending `__c` is why a standard name added in a later
  release can never clash with a name an organization made earlier.
- **Standard definitions live in files** inside the application (section 3). An organization cannot change them. Custom definitions live in the
  organization's own database tables and are changed through the object manager (`/setup/objects`).
- **A field has a type** (19 exist, section 6) and the settings that go with the type. The type of a field never changes.

## 2. What exists and where

| Thing | Where |
|-------|-------|
| The standard objects and their fields | `platform-app/src/main/resources/metadata/standard/<Object>.yml` (one file per object) |
| The fields every object has | `platform-app/src/main/resources/metadata/standard/_system-fields.yml` |
| The generated list of all of them | `docs/standard-objects.md` (never edit by hand) |
| The rules per field type | `platform-app/src/main/java/app/platform/metadata/internal/FieldRules.java` |
| The list of types | `platform-app/src/main/java/app/platform/metadata/FieldType.java` |
| Reading the files | `.../metadata/internal/StandardMetadataLoader.java` |
| An organization's own objects and fields | tables `object_definition`, `field_definition` (migration V031) |
| The read contract for other modules | `app.platform.metadata.Metadata` |
| The released list (the guard) | `platform-app/src/test/resources/metadata/standard-baseline.txt` |

## 3. Recipes

Always finish a recipe with the three commands of section 4. Start from the application's root folder (`platform`).

### 3.1 Add a standard object

1. Copy a small existing file, for example `Role.yml`, to `<NewObject>.yml` in `platform-app/src/main/resources/metadata/standard`. The file name must be the
   API name of the object plus `.yml`.
2. Set `apiName` (a capital letter, then letters and digits; no underscore, never ending in `__c`), `label`, `pluralLabel`, `description`.
3. Decide `extensible` (may organizations add their own fields? default true) and `managedBy` (leave out when the records are ordinary organization
   data; write `identity`, `security` or `licensing` when another part of the platform owns the records, see 5.3).
4. List the fields under `fields:` (section 5). The system fields (`id`, `sequence`, `description`, `ownerId`, ...) are added automatically; do not list them.
5. If another object should point to the new one, add a `LOOKUP` field there with `settings: { targetObject: <NewObject> }`.
6. Run the commands of section 4. Add a line to the delivery plan if the object belongs to a sprint story.

### 3.2 Add a field to a standard object

1. Open the object's file and add an entry under `fields:`. Put it at the end so the list keeps its order.
2. Give it `apiName` (a lower case letter first, letters and digits, no underscore), `label`, `type`, and the settings of the type.
3. Run the commands of section 4. The new field appears in the object manager and in the permission editors at once, and nobody has any permission
   on it until an access policy or profile gives one (the administrator profile has everything).

### 3.3 Change a label or a description

Edit `label`, `pluralLabel` or `description` in the file. Labels are free to change; the API name is not. Run section 4 (only the generated document
changes; the baseline does not).

### 3.4 Change a setting (a length, a number of digits, a default)

You may make a limit **bigger** (a longer text, more digits). Making it **smaller** can break data that exists; do not, unless no organization can hold a
longer value. Change the number in `settings`, run section 4.

### 3.5 Add, reorder or switch off a picklist value

- **Add**: add `- { value: NewValue, label: New value }` to `values`. The new value goes at the end; order in the list is the order shown.
- **Switch off** (stop offering it, keep existing data valid): add `active: false` to that value.
- **Never remove** a value or change its `value`: records may hold it. The baseline test refuses. Change the *label* instead if the text is wrong.

### 3.6 Retire a field you no longer want

Never delete or rename a released field. Add `retired: true` to it. It keeps existing, still holds the values records have, is shown as retired and is no
longer offered for new permissions. If the field must really go, that is a data migration and an ADR, not an edit of a file.

### 3.7 Make an object closed or open for extra fields

Set `extensible: false` (or `true`) in the file. This is safe to flip in either direction; custom fields an organization already added stay.

### 3.8 Add a system field (every object gets it)

Add it to `_system-fields.yml`. Think twice: it appears on **every** object, standard and custom, and no organization can have a field of that name any
more (they cannot clash anyway because of `__c`, but the platform's own objects can). The loader refuses a standard field with the same name.

### 3.9 Add a new field type

1. A constant in `FieldType` (label, description, which settings it takes, whether it can be required, unique or have a default).
2. A configuration record in `FieldConfiguration` if no existing shape fits (a sealed interface: the compiler lists every place that must handle it).
3. The rules in `FieldRules` (ranges, default value check) and the case in `ConfigCodec` (stored form).
4. A new migration that widens the check `field_definition_type_known` of V031 (forward-only; never edit V031).
5. A line in `FieldRulesTest` and `ConfigCodecTest`; an example in `MetadataApiIT.everyType`. Write an ADR if the type changes how records are stored.

### 3.10 Change the type or the API name of something released

You cannot, on purpose. If it is truly needed: add a new field, move the data with a migration, retire the old one (3.6). Write an ADR first.

## 4. After every change: three commands and a check

```text
./mvnw -pl platform-app test -Dtest=StandardMetadataLoaderTest                      # the files are sound (it also runs in the normal build)
./mvnw -pl platform-app test -Dtest=StandardMetadataBaselineTest -Dplatform.metadata.baseline.update=true
./mvnw -pl platform-app test -Dtest=StandardObjectsDocTest -Dplatform.metadata.doc.update=true
git diff platform-app/src/test/resources/metadata docs/standard-objects.md           # read what changed
```

- The second command rewrites `standard-baseline.txt`. **Read its diff.** New lines are expected. A *removed* line, or a changed type, means you are
  breaking a released definition: stop and use the recipe for retiring (3.6).
- The third rewrites the generated document. Commit all of it together with the YAML.
- Without these, the build fails with a message that says which one is missing. That is the guard doing its job.

## 5. The file format (reference)

An object file:

```yaml
apiName: Account              # permanent; must match the file name
label: Account
pluralLabel: Accounts
description: Free text.       # optional
managedBy: security           # optional: identity | security | licensing
extensible: true              # optional, default true
fields:
  - apiName: name             # permanent
    label: Account name
    description: Free text.   # optional
    type: TEXT                # one of section 6
    required: true            # optional, default false
    unique: false             # optional, default false
    default: Customer         # optional; written as text, must fit the type
    retired: false            # optional, default false
    settings: { maxLength: 255 }
```

Keys the loader does not know are an error (so a typing mistake cannot silently do nothing). **Quote any text that contains a colon followed by a space**
(`description: "Note: this"`), or use `>-` for a longer text; YAML otherwise reads it as a new key.

Settings per type: `maxLength`, `digits`, `precision`, `scale`, `values` (a list of `{ value, label, active }`), `targetObject`, `expression`, `resultType`,
`prefix`, `startAt`, `width` (section 6 says which type takes which).

## 6. The field types

| Type | Takes | Required | Unique | Default | Notes |
|------|-------|----------|--------|---------|-------|
| TEXT | maxLength 1-255 (255) | yes | yes | yes | one line |
| LONG_TEXT | maxLength up to 100000 (32000) | yes | no | yes | several lines |
| NUMBER | digits 1-18 (18) | yes | yes | yes | whole number |
| DECIMAL | precision 1-18 (18), scale 0-10 (2) | yes | yes | yes | |
| CURRENCY | precision 1-18 (18), scale 0-6 (2) | yes | no | yes | |
| PERCENT | precision 1-18 (8), scale 0-6 (2) | yes | no | yes | |
| BOOLEAN | none | no | no | yes | checkbox |
| DATE, DATETIME, TIME | none | yes | no | yes | default as `2030-01-31`, `2030-01-31T09:30:00Z`, `09:30` |
| EMAIL, PHONE, URL | none (fixed lengths 254, 40, 2048) | yes | yes | yes | |
| PICKLIST, MULTI_PICKLIST | values (at least one; at most 1000) | yes | no | yes | a default is an active value; several are separated by `;` |
| LOOKUP | targetObject (must exist) | yes | no | no | the record can exist without the target |
| MASTER_DETAIL | targetObject | always | no | no | at most 2 per object, only on custom objects and the platform's own definitions |
| FORMULA | expression (up to 4000), resultType | no | no | no | **stored, not calculated** until Sprint 13 and 15 |
| AUTO_NUMBER | prefix (up to 10), startAt, width 1-12 | no | always | no | counting arrives with records |

The defaults are in brackets. The authority is `FieldRules`; the unit tests in `FieldRulesTest` are the specification.

## 7. Rules for organizations (what the object manager enforces)

- A custom name is typed without `__c`; the platform adds it. Letters and digits with single underscores; objects start with a capital, fields with a lower case letter.
- Names are unique without regard to case, per organization (objects) and per object (fields).
- Up to 200 custom objects, 500 custom fields per object, 1000 values per picklist (`platform.metadata.limits.*`).
- A custom object or field can be removed; its permissions end with it; an object other fields point to cannot be removed until those fields are.
- A picklist value can be switched off, never removed; the target of a lookup never changes.
- An organization may add fields to a standard object only where the file says `extensible: true`, and a master-detail only on its own objects.

## 8. Where each later piece of the metadata work lives

| Piece | Sprint | Notes |
|-------|--------|-------|
| Relationships (one-to-many, many-to-many), what a lookup does on delete, record types | 11 | the record type becomes a standard object then |
| Draft, validate, publish, versions, rollback; dependency check before a removal | 11 | `MetadataService.deleteObject/deleteField` are where the dependency check goes |
| Page layouts, record pages, applications, navigation | 12 | rendering 19 and 20, builder 21 |
| The metadata runtime, expression engine, validation rules | 13 | the expression engine decides what a formula may be |
| Records (the `id` in the record address, the `sequence` counting) | 14 | `ObjectUsage` is the seam that keeps an object with records from being removed |
| Required, unique, default and formulas enforced on records; field security | 15 | |
| Record-level security (view-all, modify-all start to work) | 17 | |

## 9. If something fails

- *The application does not start and names a YAML file*: the message says the file, the place (the object and field) and the rule. Fix that place.
- *`StandardMetadataBaselineTest` fails with "was released and is gone"*: you removed or renamed something released. Restore it and use `retired: true`.
- *`StandardMetadataBaselineTest` fails with "not in the baseline yet"*: you added something; run the update command of section 4 and commit the baseline.
- *`StandardObjectsDocTest` fails*: run the third command of section 4.
- *A lookup is refused with "This object does not exist"*: the target name is spelled differently from the `apiName` of its file (case matters).
