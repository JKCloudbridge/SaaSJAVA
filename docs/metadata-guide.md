# Metadata guide: objects, fields and how to change them

Written for anyone who has to add or change what the platform defines: a new standard object, a new field on one, a label, a picklist value, or a
new kind of field. You do not need to know databases for most of it. Decisions behind it: [ADR-0058](adr/0058-object-and-field-definitions.md) to
[ADR-0062](adr/0062-removing-objects-and-fields.md); since Sprint 11 also [ADR-0063](adr/0063-relationships.md) to [ADR-0068](adr/0068-the-publish-ability-and-changes-made-at-once.md) (section 10). The list of what exists today is generated: [standard-objects.md](standard-objects.md).

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
managedBy: security           # optional: identity | security | licensing | metadata
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
`prefix`, `startAt`, `width`, `onDelete`, `reparentable`, `listLabel` (section 6 says which type takes which).

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
| LOOKUP | targetObject (must exist), onDelete CLEAR or REFUSE (CLEAR; REFUSE when required), listLabel | yes | yes (one-to-one) | no | the record can exist without the target |
| MASTER_DETAIL | targetObject, reparentable (no), listLabel | always | yes (one-to-one) | no | at most 2 per object, only on custom objects and the platform's own definitions; always cascades |
| FORMULA | expression (up to 4000), resultType | no | no | no | checked when saved (section 12); **calculated when records are saved, Sprint 15**; the type it gives cannot change |
| AUTO_NUMBER | prefix (up to 10), startAt, width 1-12 | no | always | no | counting arrives with records |

The defaults are in brackets. The authority is `FieldRules`; the unit tests in `FieldRulesTest` are the specification.

## 7. Rules for organizations (what the object manager enforces)

- A custom name is typed without `__c`; the platform adds it. Letters and digits with single underscores; objects start with a capital, fields with a lower case letter.
- Names are unique without regard to case, per organization (objects) and per object (fields).
- Up to 200 custom objects, 500 custom fields per object, 1000 values per picklist (`platform.metadata.limits.*`).
- A custom object or field can be removed; its permissions end with it; an object other fields point to cannot be removed until those fields are. **Since Sprint 14 an object that still has records, live or in the recycle bin, cannot be removed, and a field that records still hold values in cannot be removed** (live or in a change set); a rollback that would lose either is refused with the reason (`ObjectUsage`, [ADR-0090](adr/0090-the-answer-to-object-usage.md)). Empty the field in those records, or remove the records for good, and the removal works. The type of a field never changes, so "retyping" means removing and adding, which this blocks while values exist.
- A picklist value can be switched off, never removed; the target of a lookup never changes.
- An organization may add fields to a standard object only where the file says `extensible: true`, and a master-detail only on its own objects.
- A lookup or master-detail also has a delete behaviour, a re-parenting flag and a list label (section 10); a unique one is one-to-one.
- Record types: up to 50 per object, only on objects whose records are ordinary organization data (`managedBy` empty).

## 8. Where each later piece of the metadata work lives

| Piece | Sprint | Notes |
|-------|--------|-------|
| Relationships (one-to-many, many-to-many), what a lookup does on delete, record types | 11 (built) | section 10; `RecordType` is a standard object since Sprint 11 |
| Draft, validate, publish, versions, rollback; dependency check before a removal | 11 (built) | section 10; the check runs after every change in `MetadataLifecycle` |
| Page layouts, record pages, applications, navigation | 12 (built) | section 11; rendering with records 19 and 20, builder 21, security-aware navigation 18 |
| The metadata runtime, expression engine, validation rules, formula checks | 13 (built) | section 12; the engine is the module `expression`; `MetadataResolver` hands out the compiled rules and formulas |
| Records (the `id` in the record address, the `sequence` counting) | 14 (built) | the record store ([ADR-0084](adr/0084-the-record-store.md)); `ObjectUsage` is answered, so an object with records or a field with values cannot be removed ([ADR-0090](adr/0090-the-answer-to-object-usage.md)). The `sequence` counting is Sprint 15 |
| Required, unique, default and formulas enforced on records; field security | 15 | |
| Record-level security (view-all, modify-all start to work) | 17 | |

## 10. Relationships, record types, change sets and releases (Sprint 11)

Plain words first, then what to change.

- **Relationship.** A lookup or master-detail field links records of one object (the *child*) to records of another (the *parent*). Both ends see it: the child has the field, the parent
  shows a *list* of its children. A unique link is one-to-one. Two master-detail fields on one object (a *junction*) relate their two parents many-to-many. Nothing is stored apart from the
  field. See it on the object page, "Relationships".
- **What happens when the parent is removed** is chosen on the field: a master-detail removes its details with it; a lookup either empties the link or refuses the removal (a required lookup refuses).
  Records do not exist yet, so the data engine will carry this out; today it is stored, shown and known to the dependency check.
- **Record type.** A variant of an object that offers some of its fields and allows some picklist values. At most one is the default. A restricted record type must offer every required field.
- **Change set.** A named group of changes (create/change/remove an object, a field or a record type) that goes live **all together or not at all**. While it is open nothing in it is live and nobody
  else sees it. **Check** lists every problem and what would change; **Preview** also shows the objects as they would be; **Publish** puts it live as one numbered *release*. To fill one, choose it in
  "Changes on the object pages go" and make the changes on the object pages as usual.
- **A change made at once** (no change set chosen) is allowed to people who may both manage and publish; it is one release of one change.
- **History and rollback.** Every release is listed with what it did. The latest can be rolled back (a new release that undoes it). A removed field comes back as a definition, not with the permissions it had.
- **When something is still needed.** Removing a field a record type offers, or an object a lookup points to, is refused and the message names the record type or field that needs it. Fix it in the same
  change set (update the record type, then remove the field) and publish again.

To add a **standard** object that is a record type holder or to change the standard `RecordType` definition, follow sections 3 and 4 (the file is `RecordType.yml`; its owner is `metadata`).
To give a **standard field** a delete behaviour, add `onDelete: REFUSE`, `reparentable: true` or `listLabel: ...` under its `settings` (the loader checks them like any other setting).
## 11. Page layouts, record pages, applications and layout assignments (Sprint 12)

Plain words first, then what to change.

- **Page layout.** The structure of a record form of one object: *sections* with *fields* in them. Each field is editable, read only, required on this page, or hidden. A layout must show every required field
  of its object and must not hide it. Make them on the object page, "Page layouts".
- **Record page.** What a record page is made of: a header, the details (the fields of a layout), related lists (the records that point at this one), tabs, activity and history, in order, in the main part
  or a side panel, and the actions (edit, delete, clone). A part can be shown only **when** a field has a value or the person holds an ability; the form builds one such condition, and a rule that combines
  several (all/any) is kept as it is. There is no free text.
- **Application.** A named menu: an icon key, the profiles whose members see it (none chosen: everyone) and items in order: an object, a record page, a link to a page of the organization (`/members`,
  never an address elsewhere) or a heading. Make them at **Setup, Applications**. Members see their menu in the navigation.
- **Which layout is used.** Choose a default for the object, a layout for a profile, and for a profile a layout per record type. The layout of a record type alone is chosen on the record type. The order
  is: the profile with the record type, the record type, the profile, the default. "Which layout would a person get?" answers and names the rule.
- **Preview.** Each layout and record page has a preview that draws the structure with made-up values. It is a picture of the structure; the screens that show real records are Sprints 18 to 21.
- **Everything goes through the working set.** Choose a change set in "Changes on the object pages go" and the new things are drafted, checked, previewed and published with the rest; otherwise they go live at once
  (an administrator who may manage and publish). A rollback puts the latest release back, including layouts, pages, applications and choices.
- **When something is still needed.** Removing a field a layout shows, a layout a record page, a record type or a choice uses, a record page or an object an application opens, or the relationship a related list
  follows, is refused and the message names who needs it. Fix both in one change set and publish.

To change the **shape** of a definition (a new component type, a new behaviour, a new rule operator) edit `platform-api-contract/src/main/resources/schemas/metadata-definitions.json`, run `scripts/update-openapi`
and `npm run api:generate` in `platform-web`, extend `DefinitionSchemasTest`, and add the new word to the form that offers the list. Nothing in this sprint changes a standard definition, so the three commands of
section 4 are not needed.

## 12. Formulas, validation rules and the expression language (Sprint 13)

Plain words first, then what to change. The language is small on purpose: it can calculate and compare, and nothing else. There is no way to call code, read a file or reach another system.

### 12.1 What a formula and a validation rule are

- **A formula field** is a field whose value is worked out from other fields of the same object, for example `quantity__c * unitPrice__c`. You choose what it gives (text, number, decimal, currency,
  percent, checkbox, date, date and time, or time). The platform checks the formula when you save it; the values are worked out when records are saved, in a later release. The type a formula gives
  cannot be changed afterwards (other formulas and rules may rely on it): add a new formula field instead. Its text can be changed.
- **A validation rule** is a *condition* and a *message*. **When the condition is true, the save is rejected** and the person reads the message. So write the condition for the mistake, not for the good case:
  `status__c = 'Closed' AND ISBLANK(closeDate__c)` rejects a closed record that has no close date. A condition that has no answer (a field nobody filled in) does **not** reject. The message can be shown on a
  field of the object. A rule can be switched off without being removed.
- Both are made on the object page ("Add a field", type Formula; "Validation rules") and both go through the working set, so they can be drafted, checked, previewed and published like everything else.
  **"Check the formula" / "Check the condition"** asks the real engine: it says where the text is wrong (line and column), the type it gives and the fields it uses. **"Try it with sample values"** asks what it
  would do for the values you type (there are no records yet).

### 12.2 How to write one

- **Fields** are written by their API name, exactly as shown on the object page (`quantity__c`, `Name`). Only fields of the same object, in this version.
- **Numbers** are plain digits with an optional point: `12`, `0.5`. No exponent (`1e5` is refused). They are decimals: `0.1 + 0.2` is `0.3`. At most 28 digits before the point and 10 after it.
- **Text** is written between single quotes: `'Closed'`. To put a quote inside, write it twice: `'it''s'`. Text is compared letter for letter, so `'a'` and `'A'` are different; use `LOWER(...)` to ignore the case.
- **Yes or no** is `TRUE` or `FALSE`. **No value** is `NULL`.
- **Dates**: `DATE(2030, 1, 31)`; add or take days with `+` and `-` (`closeDate__c + 30`); `closeDate__c - startDate__c` is the number of days between two dates.
- **Operators**, from the loosest to the tightest: `OR`, `AND`, `NOT`, comparisons (`=` `!=` `<` `<=` `>` `>=`, one at a time: combine two with `AND`), `+` `-`, `*` `/` `%`, a minus in front of a number. Brackets group.
  Write `=` with one sign. `NOT` goes in front of a whole condition (`NOT (a = b)`) or after `AND` or `OR`; anywhere else write it in brackets: `x = (NOT y)`.
- **Spaces and line breaks** between the parts are fine, so a long text can be laid out over several lines.
- **What there is not:** no `.` (no method calls and no fields of other objects: those come with the data engine), no loops, no regular expressions, no power operator, no comments, no functions of your own,
  no way to ask what time it is in a formula (see 12.5).

### 12.3 No value, in words

A field nobody filled in has **no value**, and so does the empty text (the empty text is not a value). What happens:

- A calculation with no value in it gives no value: `quantity__c + 1` has no value while `quantity__c` has none. Handle it with `COALESCE(quantity__c, 0) + 1`.
- A comparison with no value in it has no value, so a rule built on it does not reject. To ask whether there is a value, write `quantity__c = NULL` (or `ISBLANK(quantity__c)`); `quantity__c != NULL` asks the opposite.
  The number `0` and the text `' '` are values.
- `AND` and `OR`: `FALSE AND (no value)` is false and `TRUE OR (no value)` is true; otherwise the answer has no value. `NOT` of no value is no value. `IF(condition, a, b)` takes `b` when the condition has no value.
- The functions that ask about a value say what they give: `LEN` of no value is 0; `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` are false; `CONCAT` counts it as nothing; the others give no value.
- `IF`, `AND`, `OR` and `COALESCE` run only the part they need, so `IF(quantity__c = 0, 0, 100 / quantity__c)` never divides by zero. A division or remainder by zero is otherwise an error that names the place.

### 12.4 The functions (the list is fixed)

| Function | What it gives |
|----------|---------------|
| `IF(condition, then, else)` | `then` when the condition is true, otherwise `else`; the two must be of the same type |
| `ISBLANK(value)` | true when the value is empty |
| `COALESCE(a, b, ...)` | the first value that is not empty (2 to 10 values of the same type) |
| `ABS(number)` | the number without its sign |
| `ROUND(number, places)` | rounded to 0 to 10 decimal places; halves round away from zero |
| `FLOOR(number)`, `CEILING(number)` | the whole number below or above |
| `MIN(a, b, ...)`, `MAX(a, b, ...)` | the smallest or largest of 2 to 10 numbers |
| `LEN(text)` | the number of characters |
| `UPPER(text)`, `LOWER(text)`, `TRIM(text)` | capitals, small letters, without the spaces at both ends |
| `LEFT(text, count)`, `RIGHT(text, count)` | the first or last characters |
| `SUBSTRING(text, start, length)` | part of the text; the first character is position 1 |
| `CONTAINS(text, part)`, `STARTS_WITH(text, start)`, `ENDS_WITH(text, end)` | true or false |
| `CONCAT(a, b, ...)` | the texts joined (2 to 20) |
| `TEXT(value)` | any value as text: plain digits, `true` or `false`, `2030-01-31` |
| `VALUE(text)` | the number written in the text; an error when it is not a number |
| `DATE(year, month, day)`, `YEAR(date)`, `MONTH(date)`, `DAY(date)` | build a date, or take a part of a date or a date and time |
| `ADD_DAYS(date, days)` | the date (or date and time) moved by a whole number of days |
| `DAYS_BETWEEN(from, to)` | the whole calendar days from the first to the second |
| `TODAY()`, `NOW()` | the day, or the day and time, of the save (**validation rules only**) |

The authority is the engine (`Functions` in `app.platform.expression.internal`); the list comes from `GET /api/v1/metadata/expressions/functions`, so the screens never hold a list of their own.

### 12.5 What a formula may and may not use

- Only fields of the same object, standard, custom and system. A formula may use another formula field, up to 5 formulas high; one that uses itself, directly or through other formulas, is refused and the message names the fields.
- **A formula cannot ask for the clock** (`TODAY()` and `NOW()` are for rules): a stored value would silently go stale. If you need "days since", use a rule, or wait for the release that works formulas out when a record is read.
- **A field a formula or a rule uses cannot be removed**, live or in a change set; the message names the formula or the rule. To remove both, put both removals in one change set (the order inside the set does not matter).
- **A formula stored before this release** (it was only kept as text) is checked now. If it does not pass it stays as it is, the object page says "This formula is not valid, so it is not calculated" with the place, and
  `GET /api/v1/metadata/formula-report` lists it; nothing else is blocked and nothing is deleted. Fix its text and save it: from then on it is checked like any other.

### 12.6 How to add a function (a change to the language: read ADR-0076 first)

1. Add one `define(library, "NAME", min, max, "NAME(...)", "what it does", clock, typing rule, code)` to `Functions.java` in the group it belongs to. The typing rule says which types it takes and gives
   (`args.expect(...)`, `args.unify(...)`); the code reads its arguments with `call.value(i)` (only when it needs them), takes the empty value into account, and fails with `call.fail(...)` or `call.failAt(i, ...)`.
   Charge long texts with `call.chargeText(...)` and check a text result with `call.result(...)`.
2. Add it to the list of this section and to the expected names in `ExpressionEngineTest.theLibraryIsListedForTheGuideAndTheScreens`; add its cases (including no value and an error) to `ExpressionEngineTest`.
3. If it can make a text, a number or a date outside what the engine allows, add the hostile case to `ExpressionLimitsTest`. If it is a new kind of danger (a function that takes a pattern, makes a big text, reads something), add a line
   to `unsafe-constructs.txt` that must still be refused, and say why in ADR-0081. The property tests (`ExpressionPropertyTest`) generate random typed expressions from the typing rules you wrote: add the function to
   `TypedGenerators` so it is exercised.
4. `./mvnw test -Dtest='Expression*Test,UnsafeCorpusTest,ExpressionSandboxTest'` runs the engine's own tests in seconds, with no container.

### 12.7 Reading the compiled rules and formulas (for Sprint 15 and later)

Ask `MetadataResolver` (metadata root): `validationRules(object)` gives the active rules ready to judge a map of values (`CompiledRule.evaluate(values, now)`: rejects, passes, or failed with the place);
`formulas(object)` gives the formulas that pass, in the order they must be calculated (`CompiledFormula.calculate(values)`; put each result in the map before the next). Values are `BigDecimal` (or a whole number
type), `String`, `Boolean`, `LocalDate`, `Instant` or `LocalTime`; a field with no entry, or null, has no value. Nothing touches the database. The artefacts are kept with the catalogue and are never older than it (ADR-0080).

## 9. If something fails

- *The application does not start and names a YAML file*: the message says the file, the place (the object and field) and the rule. Fix that place.
- *`StandardMetadataBaselineTest` fails with "was released and is gone"*: you removed or renamed something released. Restore it and use `retired: true`.
- *`StandardMetadataBaselineTest` fails with "not in the baseline yet"*: you added something; run the update command of section 4 and commit the baseline.
- *`StandardObjectsDocTest` fails*: run the third command of section 4.
- *A change set is refused with "does not exist after this change"*: something still needs what the set removes; the message names the record type or field. Update that first, in the same set.
- *A lookup is refused with "This object does not exist"*: the target name is spelled differently from the `apiName` of its file (case matters).
- *A formula or a rule is refused with "Line 1, column N: ..."*: the place is in the text you typed; section 12 lists what the language has and what it has not. "does not exist after this change" names a field the text uses
  that the object does not have (check the API name; case matters). "is stacked on N formulas" means formulas that use formulas are more than 5 high; "use each other in a circle" names the fields that depend on each other.
- *`UnsafeCorpusTest` fails with a line number*: that construct is now accepted by the engine. Do not delete the line: find what widened the grammar or the library (section 12.6, ADR-0081).

## 13. What a setup change does to records that exist (Sprint 15)

Since Sprint 15 the setup you make in the object manager is enforced when a record is saved ([ADR-0091](adr/0091-the-write-pipeline-and-its-order.md) to [ADR-0097](adr/0097-links-between-records-on-write.md)).
**Nothing is ever rewritten**: a record keeps reading as it was written. The **next save** of a record is judged by the setup of that moment.

| You change | Existing records | The next save of a record |
|------------|------------------|---------------------------|
| a text gets shorter, a number gets fewer digits | keep their value | judges only the fields the save names |
| a picklist value is switched off | keep the value | records that hold it keep it; nobody else can choose it |
| a field becomes required | keep reading, without the value | refused until it has a value |
| a field is taken out of a record type | keep their value | the value can be cleared, not written |
| a formula is edited | keep the old result | every formula is recalculated at that save |
| a rule is added or changed | untouched | the record is judged by it |

Remember when you set up access: **a field permission is explicit.** A member who may read an object but was given no field permission reads and writes no field of it, and **a new field is invisible to everyone except
administrators until someone grants it** (the matrix of the profile or access policy). A formula field is readable on its own permission: it can show a value derived from fields the member cannot read, so give it deliberately.
**Unique** is stored but not yet enforced on records (Sprint 16), and a **lookup to a user, profile, role or other object the platform owns** cannot be given a value yet (Sprint 16).

## 14. Which profiles may use a record type (Sprint 15A)

By default every record type is open to every profile. **The first time you let a profile use a type, the type closes to every other profile** (the administrators' profile too): from then on only the profiles you listed may create a record of that type
or move a record to it. Remove the last profile and the type is open again. Records that already have the type keep it, and everybody who may read the object still reads them.

- **A default per profile.** Mark one of a profile's types as its default: a new record of that profile gets it when the person names no type. Without a default of its own, the profile gets the object's default type, **unless the profile may not use
  that type**; then the record has no type.
- **A deleted profile.** Its assignment stays in the list without a name and keeps the type closed until you remove it, so deleting a profile never opens a type by accident.
- **Where.** For now through the API (`/api/v1/metadata/objects/{object}/record-types/{type}/profiles`) or a change set; the screen comes with the object manager of Sprint 19. A change made at once is a release and can be rolled back, and rolling
  back a default change puts the previous default back.
- **Users in lookups.** A lookup to the `User` object now takes the identifier of a person who is an **active member** of the organization. Lookups to profiles, roles and other objects the platform owns still cannot be given a value (they are added when a real
  field needs them).

## 15. Indexed fields, filters and links on records (Sprint 16)

**For an administrator.** Flag a field "indexed" when people will filter or sort records by it and the object has many records. Behind the scenes the platform gives the field a column of its own with an index and copies the value of every record into it; for the records that already exist this takes a little while (the status page of the object, `GET /api/v1/objects/{object}/index-status`, shows *filling* with the number of records done, then *complete*). Until it is complete, filters on the field behave as if it were not flagged.

- **Which fields.** Text, e-mail, phone, web address, picklist, auto-number, number, decimal, currency, percent, date, date and time, lookup, master-detail and checkbox. **Not** long text, multi-select picklist, time of day or formula (a formula result is only current for records somebody has saved since the formula changed).
- **How many.** Per object at most 8 text fields, 4 numbers, 2 days, 2 days with time, 4 links and 2 checkboxes. Every master-detail field counts as a link whether or not it was flagged (the records that belong to a master are listed by it). A change that would go over the limit is refused with the kind and the fields that use the slots; stop indexing one of them first.
- **Stopping.** Un-flagging a field empties its copy first; the column is free for another field only when that is done.
- **Large objects.** A filter or order on a field that is not indexed (or whose index is not complete) is allowed on an object with up to 20,000 records and refused above that, with the name of the field to flag. Searches such as *contains* and *starts with*, *not equal* and *is empty* can never use an index; combine them with a condition on an indexed field.

**Removing a record that others point at.** A lookup *clears* (the default) or *refuses*; a master-detail removes its details together with the master and brings them back together with it (the ones removed on purpose before stay removed). A required lookup always refuses. The answer to a refusal says that records refer to the record and names none. A record cannot be removed while the pointers of an older object are still being prepared; try again in a few minutes.

**Many-to-many.** An object with exactly two master-detail fields links two other objects. Read it from either side by filtering the junction on that field; the same two records cannot be linked twice.

**What this does not do yet.** Record-level security (Sprint 17); screens (Sprints 19 and 20). Uniqueness and the recalculation of stored formula results came in Sprint 16A (section 16).

## 16. Unique fields and the results of formulas (Sprint 16A)

**Unique fields.** Tick "Unique" on a text, e-mail, phone, number, decimal, lookup or master-detail field and no two live records can hold the same value in it. The platform checks every new value before it saves and the database refuses what two people save at the same moment, with the same answer: "This value is already in use."
shown at the field, never saying which record or what the value is. Things to know:
- **Exact match.** "ABC" and "abc" are different values, as in every comparison on the platform. An empty field is not a value: any number of records may leave it empty.
- **Making a field unique** is refused if two live records already share a value, and the answer says **how many** records collide (not which), so you can fix the data first. Records in the recycle bin do not count.
- **After you publish**, the platform gives the field a copy of its own (it counts against the limit of fields per kind that can be indexed, which now says "indexed or unique") and checks in the records that existed. The status page of the object (`index-status`) shows each unique field as *waiting*, *enforcing*, *conflict* or *enforced*.
  New values are refused from the first moment; if a duplicate got in during that window the field shows *conflict* with the number of records, and the check finishes by itself once you have fixed the data.
- **The recycle bin.** A deleted record does not block its value. Restoring it is refused if another record has taken the value meanwhile ("This record cannot be brought back ..."): change the value, or the record that holds it, first.
- **Limits.** A number can be unique only with at most 15 digits; a web address cannot be unique; an auto-number needs no tick (it never repeats); a field flagged unique under an older setup that cannot be checked shows *not enforced* until you edit it.
- **Many-to-many.** The same two records cannot be linked twice by a junction object; the status page shows this as the pair of its two parents.
- Record-level security (Sprint 17) does not hide the fact that a value is taken: a unique field cannot do that. The check looks at every record of the organization, and the answer is the same words whether or not the person can see the record that holds the value; it never says which record or whose it is (Sprint 17A).

**Results of formulas after an edit.** When you change a formula the results stored in existing records are brought up to date in the background, a batch at a time, without changing the record's version or its "last changed" time and without an audit record per record (one per finished pass, with counts). Until it has finished:
- a **filter or a sort on a formula field is refused**, with the reason ("The results of this object's formulas are being brought up to date ..."); everything else works, and records show the stored result they had;
- `GET /api/v1/objects/{object}/formula-recalculation` says where it is (`NEEDED`, `PENDING`, `RUNNING`, `CLOSING`, then `CURRENT`) and how many records were looked at, rewritten and could not be calculated. `POST` on the same path starts a pass by hand (ability: publish metadata), for example after records were changed by a path that
  does not calculate formulas. There is no screen for this yet.
- A record whose formula **cannot be calculated** (a division by zero) loses the stored result of that formula and is counted. A record that was in the recycle bin comes back with the results of today.
- Once the formulas are current, a formula field can be used in a filter or a sort like any other field (read from the stored result, so the rule for fields without an index applies: allowed on an object with up to 20,000 live records).

## 17. Who sees which records, files, imports and search (Sprint 17)

**Who sees which records.** Until now a person who could read an object read all of its records. From Sprint 17 an administrator with the ability *Manage sharing* decides, for each object, what the **organization-wide default** is (API: `/api/v1/sharing/objects/{object}`):
- **Public Read/Write** (what every object is until you choose): everyone who may use the object sees and changes every record. Nothing changes when you upgrade.
- **Public Read**: everyone reads every record; only the owner (and the ways below) changes one.
- **Private**: a person sees their own records and what the ways below give them.

The ways to give more are all added together (nothing ever takes access away): the **role hierarchy** (the switch "grant access using the hierarchy": a manager reads the records of the people below them; reading only, never changing), **sharing rules** (records owned by the members of a role, by the members of a group, or records whose fields meet a condition, given to a group, for reading or for reading and changing), **sharing one record** with a person, a group, a role or a role and the roles below it (the owner, or anyone who can change the record, does it; at most 100 shares a record; see the next paragraph), and the object permissions **View all** (reads every record of the object) and **Modify all** (reads, changes and deletes every record of the object).

**Sharing a record with a role (FA2B).** On the record page, **Sharing**, choose *Share with*: *A person*, *A group*, *A role* or *A role and the roles below it*, search the list the page offers, choose the level (*Read*, or *Read and change*) and press Share.
- *A role* reaches the people whose own role it is, and nobody else. *A role and the roles below it* reaches the people in that role and in every role under it, at any depth, and nobody above it or beside it. A person with no role is reached by neither.
- The roles above are not reached by a share to a role below them. They read what the people below them own through the role hierarchy (the object must be Private with the hierarchy switch on), and that is reading only.
- Sharing the same role again changes the level and, if you choose the other kind, the kind. There is one share of a record for each person, group or role. A share to a role counts toward the 100.
- A share never gives the right to delete. Taking a share back (*Remove*) takes effect at the next question, and so does moving a person to another role or moving a role in the tree.
- Removing a role or a group removes the shares made to it. The list of roles the page offers shows names only, never the people in them, and only to someone who may share that record.
- To keep the people of one department apart, give each department its own role under the level above it (Setup, Roles) and share to that department's role alone; to reach every department, share to the level above them *and the roles below it*. People hold one role each.

Things to know:
- **Delete is never shared.** Only the owner, someone with Modify all, and everyone when the default is Public Read/Write can delete a record.
- **A person who cannot see a record is told it does not exist**, with the same words as for a record that was never made. A person who can read a record but not change it is told they may not change it.
- **Changes are seen at the next question** (a new default, a rule, a group or role change, a share), on every server.
- **Giving a record away** (changing its owner) is its own action; the new owner must be a member. The previous owner keeps access only as the ways above say.
- A field used by a sharing rule cannot be removed while the rule exists. At most 50 rules an object. If more than 20,000 distinct people stand behind one person's access, the platform reports it and shows that person less, never more; use group-based rules.
- **Changing and removing follow the same rules** (Sprint 17A). To change or remove a record, a person must be allowed to by the ways above: someone who cannot see the record is told it does not exist (even when the request is badly formed), someone who can see it but not change it is told they may not. The same holds for restoring a record from the recycle bin and removing it for good, and the bin shows each person the records they could bring back. A master record takes its detail records with it when removed or restored only if the person may remove those too.
- **A link only to a record you can see.** A lookup or master-detail can point only at a record the person can read; any other identifier is answered "The linked record does not exist.", exactly like an identifier that is not there. Reading a record never shows more of a linked record than its identifier when the person cannot read it.

**Files.** A record can have up to 20 files of up to 25 MiB. A file can be seen and downloaded by whoever can see the record, and added or removed by whoever can change it. The platform keeps only a cleaned name and decides the type itself; programs, scripts and web pages are refused, and every download is an attachment. Files go with the record when it is removed for good (not while it is in the recycle bin). If the file storage is down, files answer "service unavailable" and everything else works.

**External identifier and searchable fields.** On a text or number field, tick **External identifier** to say "this holds the record's number in another system": it becomes unique and indexed, and an import can update the record that has it. On a text, long text, e-mail, phone or picklist field, tick **Searchable** (at most four fields an object) and the object can be searched by words: all the words must be there, accents and capitals do not matter, and the last word may be only a beginning. A field is searched when the status page of the object (`index-status`) says its words are *complete*; a person never finds a record by a field they may not read.

**Import.** A CSV file (UTF-8, a header row, at most 20 MiB and 100,000 rows) creates or updates records through the same checks as typing them in, as the person who sends it. A column is mapped to a field (`Column` to `field__c`); a link is written either as the other record's identifier or, with `field__c:externalIdField__c`, as the other record's external identifier. An empty cell leaves a field as it is. Rows that fail are in a report you can download, correct and send again; **a dry run** checks everything and saves nothing. An import cannot be undone; stopping it keeps what it already wrote.

**Bulk.** The API can create, change or delete up to 200 records in one call, each with its own result; a call can be "all or none".

## 18. Lists, forms and saved views (Sprint 19)

Every object you define gets a **list**, a **create form** and an **edit form** with no setup beyond the object itself. What a person sees is decided by what you set up, and by what they may do.

**What the form shows.** The page layout the object uses for the person's profile and the record type (section 11). A field the person may not read is not on the form at all; a field they may read but not edit is shown as plain text; a field a layout marks *read only* or *hidden* behaves so on this page; a formula and an auto-number are always shown as text, and a formula's result appears (marked "will be calculated when saved") once the person has changed something and the system has judged the change. When an object has **no layout**, the system lays the fields out itself: the fields in one block, the system fields (owner, created, last changed) in another. Choose a layout for the object if you want a different order or blocks.

**Record types.** If the object has record types, the form offers the types the person's profile may use (section 14) and shows only the fields and the picklist values the chosen type allows. A record keeps the type it has, so someone who lost a type can still open and save a record of that type.

**What a record is called.** In a list, a heading and a link, a record is named by one field: the first **searchable** text field the person may read (so flag the field people look records up by, section 17), otherwise the first required text field, otherwise the first text field, otherwise the auto-number. People are named by their display name, as long as they are active members.

**Mistakes are pointed out before Save.** A moment after someone changes a field, the system tells them what a save would be told: a required field left empty, a value that is too long or has too many decimals, a duplicate in a unique field, **a validation rule that would refuse** (the rule's message appears beside its field). Nothing is stored by this, and the rules are judged by the same engine as the real save.

**When two people edit the same record.** The second person to save is not overwritten and does not lose their work: they are told the record was changed, shown which fields the other change touched, and their own changes are kept on top of the newer version wherever they did not collide. Where both changed the same field, they choose. Then they review and save again.

**Saved views.** On any list a person can save the filter, the order and the columns under a name. A **personal** view is only theirs. A **shared** view is seen by everyone who may read the object; making, changing and removing one needs the ability **Share list views** (give it in a profile or an access policy; administrators hold it). A view never widens what anyone can see: opening it asks the same question for the person who opens it, so a field or a record they may not reach stays out of reach. A view that names a field the person who saved it could not read is refused. Up to 50 personal views per person and 50 shared views per object.

**Search, change several records, import.** The search box on a list uses the words of the object's searchable fields (section 17). A person with the right to change or delete can select rows and change a field on all of them or delete them; each record is judged on its own and the report names the ones that were refused. **Import** (a button on the list, for someone who may create) takes a CSV file: choose the file, say which column fills which field, check it first (nothing is saved), then import; the report of refused rows can be downloaded and corrected.

**Not here yet.** Editing a value in a cell of the list, a count on each view, and showing one view to a single group are not built.

## 19. Record pages: which page people get and how a record looks (Sprint 20)

A **record page** is the screen of one record: a card with its name and a few values, then the page's blocks and tabs, and the actions a person may use. You build the pages in Setup (section 11); this section is about **which page a person gets** and what they see.

**Which page.** Choose it with a **record page assignment**, in this order: the profile together with the record type, the record type alone, the profile alone, the object's default. The first rule that applies wins. A record page does not change with the application the person came from. Deleting a profile is never blocked; an assignment of a deleted profile is ignored. Assignments are made on the object's **Record pages** section (section 20).

**When you set nothing up.** Every object still has a record screen: the details on a **Content** tab and, if other objects have a link to this one, a **Related Records** tab with a list for each link (five rows, then "View all"). The card shows the record's name and a few of its values.

**What a person sees is cut to what they may reach.** A related list of an object they may not read, or one that would show a field they may not read, is not there at all; the same for a tab of the organization that was removed and an action their permission does not allow. A condition that tests a field they may not read treats it as empty.

**The details follow the page's layout.** The layout a record page names is the one its details use, for everyone who sees that page; people still see only the fields they may read, and edit only the ones they may edit. A page with no layout of its own (the platform's own page) uses the layout the object has for the person's profile and record type (section 11).

**Reading and changing.** A record opens as text. **Edit** turns the details into the form of section 18, with **Save** and **Cancel** beside it; **Clone** starts a new record from this one's values (never its owner, a unique value or an identifier from another system); **Share** opens who the record is shared with and its owner; **Delete** asks first. Which of these appear is the record page's own list of actions, cut to what the person may do.

**Not here yet.** Activity and history components are accepted and not drawn; pages that differ by device; custom buttons.

## 20. The setup screens: building an application with the mouse (Sprint 21)

Everything in sections 2 to 19 can be done in Setup without touching the API. This section is the map.

**An object's page is a row of sections**: *Fields*, *Relationships*, *Record types*, *Page layouts*, *Record pages*, *Validation rules*, *Sharing* and *Details* (the labels, and removing the object). The address names the section, so you can bookmark it. *Custom buttons* is shown greyed out with "Soon": it is not built.

**Where changes go.** On the object pages, Applications, Tabs and Change sets, the bar at the top chooses where a change goes: **live at once** (one step in the history) or **into an open change set** (nothing is live until the set is published). Everything below follows it, **except Sharing**, which is always live (see below).

**Building a page layout.** *Page layouts* → *New page layout*, or *Build* on one. The **palette** on the left lists the object's fields (a star marks a required one); drag a field into a section, or press Enter on it to add it to the section marked *Adds go here*. Click a section to make it that one. In a section you can rename it, give it one or two columns, drag its fields (or use the arrows) to put them in order, set each field's behaviour (editable, read only, required on this page, hidden), move a field to another section, and take it out. A field is on a layout once; placing it again moves it. A warning lists the required fields that are not on the layout yet, and the system refuses to save until they are. **Preview** draws the layout you are building with sample values. **Save** sends it.

**Building a record page.** *Record pages* → *New record page*, or *Build*. Choose the layout the details use. Then arrange the **tabs**: the palette has *Standard* (Details and a list for each link other objects have to this one), *Tabs* (tabs made in Setup, Tabs) and *Custom pages*. Drag a chip into a tab, or drop it on the dashed *Add a tab* to start a new tab with it. A custom page is shown through a tab: if no tab shows that page yet, one is made, with the page's name, when you save (it is marked *Made when saved*). *Settings* on a part opens its columns, its row limit and when it is shown. The header, the side panel, lists outside the tabs, the actions and every condition on when something is shown are in the forms below the tabs.

**Which record page people get** is chosen under the list of record pages (section 19). Unlike a layout, a record type holds no page of its own, so a record type can be chosen there on its own. *Which record page would a person get?* asks the system and names the rule that decided.

**Tabs** (*Tabs* in the Setup menu). Every object already has a tab. A **custom tab** shows a **custom page**: a script you upload. *New tab* asks for a name, a label and either an existing custom page or a script file for a new one. The script is stored by the platform and runs only in the browser of the person who opens the tab, inside a sandbox, with that person's own permissions; the platform never runs it on its servers. *Replace the script* uploads a new version. A page that a tab still uses cannot be removed; the message names what uses it.

**Applications** (*Applications* in the Setup menu). *New application*, or *Build*. Give it a name, a label, an icon key and the profiles that see it (none ticked: everyone). Then build the **navigation**: drag objects and custom tabs from the palette into the list in the order members should see them; *Other* has a heading, a link (a path inside the organization) and a record page, finished in the row. An entry can have a label of its own. Members see an application as soon as it is saved live or its change set is published.

**Which profiles use which record type** is a grid under the record types (section 14): tick a profile to let it use a type; a type with no ticks is open to everyone; *Make default* chooses what a new record starts with.

**Sharing** (the *Sharing* section; needs the ability *Manage sharing*). Three things per object: the **default access** (private, public read only, public read and write), whether the **role hierarchy** gives access, and **sharing rules**. A rule gives the records owned by the members of a role (or of a public group), or the records that meet a condition on indexed fields, to a role, a role and those below it, or a public group, for reading or for reading and writing; a rule never gives the right to delete. **Sharing is live at once for everyone and is not part of a change set**; the section says so. An object nobody has set up lets every member who may read the object read every record.


## 21. Workflows: automation you define as data (Sprint 22)

A **workflow** says: *when this happens to a record of an object (or at this time), if this condition is true, do this.* It is
a definition like a validation rule: you draft it, check it, publish it (live at once, or in a change set with other changes)
and roll it back with the release. Sprint 22 delivered the engine and its API; Sprint 23 added the actions that change records,
retries, dead letters and replay (section 22) and the screens: **Setup, Workflows** (the list and the builder) and **Setup,
Workflow runs** (the history). The API stays the same door: `/api/v1/metadata/workflows` and `/api/v1/workflows/executions`.

**What starts a workflow (the trigger).** A record was created, changed, or moved to the recycle bin
(`RECORD_CREATED`, `RECORD_UPDATED`, `RECORD_DELETED`); a record was changed and one of the named fields was among the changed
ones (`FIELD_CHANGED`); or a time: every day at a time of day (`EVERY_DAY`), every week on a day (`EVERY_WEEK`), once at one
moment (`DATE_TIME`), or a number of days after the date in a date field of each record (`DAYS_AFTER_DATE`). A scheduled
trigger has a time zone (`UTC` unless you name another, for example `Europe/Berlin`) and may have a **selection**, a filter that
says which records it considers (otherwise every record of the object). Triggers from external systems come with the
integration sprints and are refused until then.

**The condition (optional).** One expression in the same language as validation rules and formulas; it must give true or
false and may use `TODAY()` and `NOW()`. The workflow runs for a record only when it is true. It is judged on the record **as
it is when the execution runs**, a moment after the change, not as it was at the change. There is no "old value": "changed to
Submitted" is written as a condition on the current value (`status__c = 'Submitted'`) together with a trigger on `status__c`.
You can try a condition on sample values before you save it (`POST .../workflows/{object}/check`).

**The actions.** In order. **Publish event** announces a named event (`workflow.signal.<name>`) that says which object, record,
workflow, execution and step, and carries no value of the record. Since Sprint 23 there are three more, described in
section 22: update a record, create a record, and send a mail. Starting an approval, an in-app notification and calling an
integration come with Sprints 24 to 28.

**When it runs.** Always **after** the change has been saved, never inside it: a workflow cannot reject, change or slow down a
save (validation and permissions stay where they were). Each cause makes **one execution**, even if the platform delivers the
event twice or two servers see it. An execution is queued, then picked up by a worker within a second or two. Timed workflows
start up to about 15 seconds after their time.

**What you can see afterwards.** Every execution is recorded: which workflow and which **version** of it, the record, the
status (`QUEUED`, `RUNNING`, `COMPLETED`, `DEAD_LETTERED`, `CANCELLED`, `SKIPPED`; `FAILED` only for runs made before Sprint 23), a reason when it did not run to the end, how many
times a worker picked it up, and each step. The reasons are fixed words: `condition_false`, `condition_not_evaluable` (the
condition could not be worked out for that record, for example a division by zero), `record_gone`, `record_restored`,
`workflow_inactive`, `feature_off`, `object_missing`, `workflow_removed`, `action_failed` (with the type of the failure),
`attempts_exhausted`. The history never keeps a value of a record, the condition or anything you typed. It is kept for 90
days by default (never fewer than 30).

**Versions.** Changing the trigger, the condition or the actions makes a **new version**; changing the label or the description
or just switching it on or off does not. An execution always runs the version it was queued under, so publishing an edit never
changes a run that is already waiting. Switching a workflow **off** (or removing it) also stops the executions that are still
waiting. Old versions that no execution uses are cleaned up a week after they were replaced.

**What a workflow cannot do yet.** Start an approval (Sprint 24), tell a person in the application or write the words of a mail
(Sprint 25), start before a save (Sprint 25), call an external system (the integration milestone) or react to one. Changing records,
sending a mail, retrying a failed step and replaying a dead letter are Sprint 23 (section 22).

**What stops a removal.** A field or an object that a workflow names (in its trigger, its condition or its selection) cannot be
removed; the refusal names the workflow. Switch off and remove the workflow first, or change it.

**Who may.** *View workflows* reads definitions and the history; *Manage workflows* creates, changes, switches and removes them
and tries conditions; making a change live also needs *Publish metadata changes*, and whoever checks or publishes a change set
that contains a workflow needs *Manage workflows* as well. The organization's plan must include the feature **workflows**
(a workflow can be made and switched on only then; switching off and removing are always possible).

## 22. Workflows that change the world: actions, retries, dead letters and replay (Sprint 23)

Decisions: ADR-0138 (the actions), ADR-0139 (how a workflow writes a record), ADR-0140 (loops), ADR-0141 (replay and the key of
an effect), ADR-0142 (retries), ADR-0143 (dead letters and parked events), ADR-0144 (what is left out).

**The actions that ship.**

| Action | What it does | What you give it |
|--------|--------------|------------------|
| Update record | Sets fields of the record that started the workflow | the fields, each a value or a copy |
| Create record | Makes a record of an object, owned by the owner of the record that started the workflow | the object, the fields, and optionally a link field that points back to the record |
| Send e-mail | Queues one mail to the person the record names | a user field or an e-mail field of the record, and the mail (one platform mail in this release) |
| Publish event | As in section 21 | the name of the event |

*A value* is either **written here** the way a field's default value is written (`true`, `2030-01-31`, the digits of a number,
values separated by semicolons for a multi-select) or **copied from a field of the record that started the workflow** (a copy of
a field with no value empties the field it is copied to). It is never a formula. A link field is never given a written value; it is
copied from a link field of the same kind.

**What the platform checks when you save.** The names of fields and objects, that a field can be set at all (not a system field,
not a formula or auto-number, not retired), that a written value fits its field, that a copy goes between fields of a kind that fit,
that a created record is given every required field that has no default, that the field of a mail names a user or an address, the
limits (actions per workflow, fields per action, the length of a value) and the number of attempts. **Try the actions** before you
save: the builder asks the platform to check them against the object and shows each problem under the action it is about; nothing
is stored. Every field an action names is a dependency: a field, or an object, that a workflow still names cannot be removed, and
the refusal names the workflow.

**How a workflow changes a record.** As **the platform**, not as a person: the member gate and the permissions (object, field and
record) are not asked, because nobody is behind the change. Everything that is true of every record is still asked, in the same
order and by the same code as for a person: the type of each value, required fields, the record type, links, unique values,
validation rules and formulas. A record the workflow changes keeps its owner, gets no new share, and its version goes up once, as
for any change. An update that would change nothing is not a change. The change is audited with the workflow and the run, with no
person, and the event it makes tells the workflows it may start that it came from a workflow (the chain below).

**A mail** says that a record of an object in an organization needs attention and links to it on the organization's own address.
It never contains a value of the record, not even its title. The person must be an active member (a user field) or the address in an
e-mail field. An organization's workflows queue at most 500 mails an hour (a step waits for its next attempt past that).

**When an action fails.**
- A failure that a later attempt may not have (a lock, a lost connection, someone changing the record at the same moment) is
  **retried**: the run waits, off every worker, and tries the step again after 30 seconds, then about a minute, two, four, up to an
  hour, with a little randomness. An action has **5 attempts** unless you lower it in the builder (you cannot raise it). The history
  shows each step with its attempts, when the next one is due and why it waits.
- A failure that no retry can mend (a value the field does not take, a required field left empty, a rule that rejects the record, a
  value already in use, a linked record that is gone, a person who is not a member) is **not** retried. The run becomes a
  **dead letter**: the status "Needs a person", with the step that failed, the number of attempts, a fixed reason in words and the
  *type* of the failure. It never keeps the text of a rule, a value or a library's message.
- A run claimed again and again because a server kept stopping is also set aside as a dead letter ("attempts_exhausted"). A crash
  is not a failed attempt, and a failed attempt is not a crash: they are counted apart.

**Replay.** A person with the ability **Replay workflow executions** (separate from managing workflows) can run a dead letter again,
one or many at once. A replay makes **one new run** that starts at the step that failed (or a later one you choose, to pass over a
step that can never succeed), against the record **as it is now**. The steps that completed are not run again, so nothing that
already happened is done twice, and the condition is not judged again: you have decided to run it. A replay of a record that is gone
ends as "record gone"; a workflow that was switched off or removed, or a plan without workflows, ends the replay as it ends any run.
A dead letter is replayed **once**: pressing Replay twice, or two people at the same moment, make one new run, and the second answer
names the first. If the replay itself dead-letters it can be replayed in turn. A dead letter is kept for 180 days (never fewer than
30), longer than a completed run (90).

**Cancel.** A run that has not started, including one waiting for its next attempt, can be cancelled by a person with the ability to
manage workflows. A run that is running or finished cannot.

**Loops.** A change made by a workflow can start workflows. The platform allows that on purpose (one workflow updates a record and
another, on that update, sends a mail) and stops what would go round: a workflow that is already part of the chain of changes is not
started again (the run is made and shown as "Skipped: loop detected"), and no more than **5 workflows in a row** can follow one
change ("depth exceeded"). Past 600 runs a minute for one object the rest are delayed, never dropped.

**Events the platform gave up on.** If the part of the platform that starts workflows kept failing on a saved change, the event is
set aside. The list of runs shows those events and a person with the ability to replay can **put one back**; nothing is done twice.
A release whose schedules were never made makes them when its event is put back.

**What is not here, on purpose.** Starting an approval (Sprint 24), an in-app notification and mail text written by an
administrator (Sprint 25), calling an integration (Milestone M7), a formula as a value, steps that wait, branches, and "continue
after a failed step". ADR-0144 lists them with what each waits for.

**The screens.** *Setup, Workflows*: the list (switch on or off, remove, open the builder, open the runs) and the builder: the name,
when it starts (the kinds the platform offers, with the properties each uses), the condition (checked and tried on sample values),
and the actions, built by dragging an action type from the palette (or choosing it) into a list you can reorder with the buttons.
Everything the palette, the settings and the limits show comes from the platform, not from the screen. *Setup, Workflow runs*: the
list with filters, the dead letters with counts by workflow and reason, the parked events, Replay and Cancel (one and many), and the
detail of a run with its steps.

## 23. Approvals: a record decided in steps (Sprint 24)

For administrators. An **approval process** decides a record in steps. When a person submits a record, it is **locked**; each step asks the people the
process names; when the request ends the record is unlocked and the process may set a few fields of it. The decisions in this section are
[ADR-0145](adr/0145-the-approval-model-processes-versions-instances-and-states.md) to [ADR-0153](adr/0153-what-sprint-24-leaves-out-and-why.md).

**What you need.** The plan feature `approvals` (without it no process can be made and no request can start) and the abilities: `approvals.view` to see
the processes, `approvals.manage` to build them and to help a stuck request, and `metadata.publish` as well to make a change live (the same two-key rule as
for workflows). `approvals.view-all` shows every request for the records a member can read; `approvals.override-lock` lets a member change a locked record
and end a request. A person who is **asked** to decide needs none of these: they act because of the task they hold.

**Build a manager-then-finance approval.** Setup, *Approval processes*, choose the object, *New approval process*. Give it a name and a label; add the
entry criteria if only some records may be submitted (for example `amount__c > 100`; empty means any record). Drag a *step* in (or choose it in the palette),
call it *Manager*, then drag *who is asked* rules onto it: a named person, a public group, the owner of the record, a person named in a field of the record.
Add a second step *Finance* the same way. For each step choose whether **any one** approver decides it or **every** approver must approve. One rejection ends
the whole request. Under *Outcomes* say which fields the process sets when the request is approved, rejected or withdrawn, to a literal you type (a
literal only: nothing is calculated). *Who would be asked?* works out, for a record you choose, the very people a real request would ask and why somebody is left
out. *Check the process* tries it against the object. Save: it is live at once, or goes into the change set you chose.

**Who gets left out.** The people are worked out when a step starts and then kept: a later change of a group or a role does not move a task that exists.
A person who is not an active member, who holds no licence, or who submitted the request (unless you ticked *the person who submits may also be asked*)
is left out and the history says why. If nobody is left for the **first** step the submit is refused and nothing is locked. If nobody is left for a **later**
step the request waits, shows *Nobody can decide this step*, and a member with `approvals.manage` asks somebody (*Ask someone as well*). You can name a
**fallback** approver (a person or a group) who is asked only when the rules leave nobody.

**What the lock does.** While a request is pending nobody can change or remove the record, whatever their permission: not through the screen, a bulk call,
an import, a workflow, a change of owner, a file, or a delete of a record that points at it. They are told *This record is being approved…* (`RECORD_LOCKED`).
Reading, searching and **sharing** are unaffected. A member with `approvals.override-lock` may change it anyway; that is audited and written into the
history of the request. The lock opens the moment the request ends, however it ends.

**What a delegate sees.** A member can name a delegate for a range of days (*Delegations*). While it is in force the delegate is asked **beside** the
delegator, who keeps the task, so someone who comes back early is not locked out. Tasks that already exist are not moved, a delegate's own delegation is not
followed, and the history shows who decided for whom.

**When the outcome cannot be written.** The decision stands. The record is unlocked, the request shows the reason (for example *A validation rule refused the
change*), the history says *The record could not be updated*, and a member with `approvals.manage` can make the update again once the cause is gone (it is
made on the record as it is now, so a record changed in between is refused and you read it again).

**Changing a process while requests are waiting.** A request keeps the version of the process it started under. A process cannot be removed, and neither can
its object, while a request is waiting; switch it off (it starts nothing new, the waiting ones finish) and remove it later. If the plan loses the feature,
nothing new starts but waiting requests finish.

**After a request ends.** A workflow can follow it: the trigger *An approval ends* (any process or one, any outcome or one) starts a workflow, and the
action *Start an approval* submits the record that started a workflow, once, and does not wait for the people.

**Not possible in this release.** Sending a request back one step, parallel or conditional steps, approving by e-mail, approving
many at once, a permission as an approver and a platform-wide unlock ([ADR-0153](adr/0153-what-sprint-24-leaves-out-and-why.md)).

## 24. Notices, reminders, automation before a save and the failure view (Sprint 25)

Decisions: [ADR-0154](adr/0154-the-notification-model-in-app-notices.md) to [ADR-0166](adr/0166-what-sprint-25-leaves-out-and-why.md).

**The bell and the list.** Every member has a bell in the header and a list at *Notifications*: a task waiting for them, a reminder, the decision on a request
they made, a message from a workflow, and notices about the organization's trial. A notice is a short title and a line of text with a link to the record or
to the inbox. It holds no value of the record, not even its title: the person opens the record, and the record's own permissions decide what they see.
They mark one notice, or all of them, as read; read notices are removed after 30 days and unread ones after 180 (never before 7).

**What each person receives.** *Notifications → Preferences* has a switch for each kind of notice (approvals, workflows, account) and each way (in the
application, by mail). Everything is on until someone changes it. Account notices, such as the end of a trial, cannot be switched off. A person who
switched a kind off still has the work itself (the task is in the inbox): a switch changes the message and nothing else.

**The words of notices and mails.** *Setup → Notification text* shows the platform's words for each kind and lets you write your own for the four kinds an
organization may word: *a task is waiting*, *a reminder*, *a request was decided* and *a workflow asks you to look*. A text has a title (at most 80
characters) and a body (at most 500). It may use the placeholders the page lists for that kind, written in braces, such as `{organization}`, `{object}`,
`{process}`, `{step}`, `{workflow}` and `{outcome}`: the names of things you set up, never a value from a record. It may not contain a link, an
address, markup or a placeholder that is not listed; the page says which line is wrong and does not repeat what you typed. *Check* shows the result with
sample names and stores nothing. A text is part of setup: it can be drafted in a change set, published and rolled back like a layout, and *Back to the
platform's text* removes yours. A mail uses your body; its subject and its link are always the platform's.

**Telling someone from a workflow.** The action *Tell a member* (`NOTIFY`) sends one notice about the record to its owner or to the person a user field
of the record names. It is sent once however often the step is tried again. A person who switched these notices off gets none and the workflow still
completes. If the field is empty, or the person no longer belongs to the organization, the workflow stops at that step with a reason you can read in its
history.

**Automation when a record is saved.** In the workflow builder choose *When saving*: *a record is created* or *a record is changed*. Such a workflow has one
action, *Set fields*, which sets fields of the record being saved to a written value or a copy of another field of the same record, under an optional
condition. It happens inside the save, so there is no waiting and no second change: the record is stored with the values already set, and everything a
person's input must pass afterwards (required fields, links, unique values, validation rules, formulas) is applied to them too. If a rule refuses the
result, the save is refused with the rule's own words. An object may have 10 such automations; they are judged on the record as saved, run in the order of
their names, and a later one wins a field two of them set. It cannot change another record, send anything or call out: use a workflow after the save for
that. Switching a workflow off stops it at once.

**Reminders and escalation in approvals.** In the approval builder, *Reminders* has two optional numbers of hours. After the first, the people asked for
the active step are reminded (at most three times, one reminder every that many hours); after the second, the fallback approver of the process is asked as
well and becomes one more person who may decide. When the reminders are used up, the request is marked *overdue* and shown to those who watch for requests
needing attention. A step that was decided, and a request that was recalled or ended, is never reminded. Escalation needs a fallback approver in the
process, and the page says so.

**The end of a trial.** An organization on a trial keeps working for 7 days after the trial ends. Its administrators are told 7 days and 1 day before it
is suspended, and when it is, by mail and in the bell. Suspension is the same suspension a platform administrator can do by hand: sign-in stops and a
platform administrator reinstates it once a plan is active. Nothing is ever deleted.

**The failure view.** *Setup → Automation health* shows counts for your organization: executions waiting, retrying, set aside for a person, stuck for more
than ten minutes, events the platform gave up delivering, approval requests nobody can decide, steps that are overdue, mails waiting and mails that could
not be sent. Each count that needs attention links to the screen that lets you act. It shows numbers only. It needs the ability *View the health of automation*,
which the administrator profile holds. Platform staff see the same counts for all organizations in the console, with no names.

**Not possible in this release.** A digest, push messages, text messages, a field of the record in a notice, a link in your own text, naming a person or
a group as the recipient of a workflow notice, a second escalation level, before-save automation that changes other records, and a weighted share of the
queue by plan ([ADR-0166](adr/0166-what-sprint-25-leaves-out-and-why.md)).

## 25. Shipped objects, plan features and kept records (Functional track FA1)

Decisions in [ADR-0181](adr/0181-the-solution-framework.md). Two attributes on an object file say how it behaves; both are written in the object's YAML file and both are part of the baseline (dropping one from a released object fails the build).

- **`requiresFeature: <feature key>`** - an organization whose plan does not have the feature does not have the object at all. The object manager, the data catalogue, the permission decisions, the record endpoints, the form, search and the recycle bin all answer "not found", with the same words as for a name that was never defined. The feature is asked when the object is looked up, never stored, so a change of plan shows at once. Data is never deleted by losing the feature.
- **`retainRecords: true`** - every record of the object is kept. Nobody can delete one, restore over one or purge one (the answer is `The records of this object are kept.`), no profile, access policy or member can be given the delete right on it (and the screens draw no control for it), the recycle-bin job skips it, and the database refuses a delete and a soft delete even for its owner. What a kept record can still do: be changed (including a `retired` field), be shared, have files, be read.
- An object that has either attribute cannot be `managedBy` another module (its records must be in the generic store).

### 25.1 Where a shipped object lives

A shipped object is a platform definition like a standard one, but its file lives with the solution that needs it: `platform-app/src/main/resources/solutions/<key>/objects/<Object>.yml` (the metadata module reads that folder by pattern). The solution's own file `solutions/<key>/solution.yml` names the objects it brings and must say the same feature. Everything else in this guide (field rules, never delete or rename a released definition, `retired: true`, the three commands of section 4) applies unchanged. A shipped object is `extensible: false`: an organization cannot add fields of its own to it, but it can still build approval processes, workflows, layouts and record types on it.

### 25.2 What an administrator sees

Nothing of it in the object manager's list while the plan lacks the feature. With the feature: the object appears like a standard one (not editable, not removable), the shipped application appears in the application launcher for members who hold one of the shipped access policies, and the policies appear in the access-policy list marked "Comes with the platform" (they can be given to members and groups and nothing else).

## 26. Dependent picklists, a list the organization owns and number series (Functional track FA2A)

Decisions: [ADR-0177](adr/0177-dependent-picklists.md), [ADR-0182](adr/0182-the-organizations-own-list-of-a-platform-picklist.md), [ADR-0183](adr/0183-a-field-only-the-platform-writes.md), [ADR-0184](adr/0184-number-series-declaration-formats-and-counters.md), [ADR-0176](adr/0176-number-series-and-the-first-approvers-number.md).

### 26.1 A picklist that depends on another (for administrators)

A single-value picklist can depend on another single-value picklist of the same object (for example Sub-category on Category). In the object manager open the field, **Edit**, and under **Depends on** choose the other picklist; a grid appears with the values of the other picklist down the side and the values of this one across the top. Tick, for each value of the other picklist, the values that go with it. A row with nothing ticked means nothing goes with that value. At twelve or more values a filter appears above the grid. The rules, which the server checks and states in its own words:

- one level only: the other picklist must not depend on a third, and a picklist cannot depend on itself; a multi-select picklist cannot depend on anything;
- at most 200 values of the other picklist and 5,000 pairs per field (`platform.metadata.limits.max-dependency-controlling-values` and `max-dependency-pairs`);
- the other picklist cannot be removed, retired or changed to another type while a field depends on it (the dependency graph, in any order of the changes of a change set);
- on a record, a value is accepted only when the record has a value in the other picklist and the pair is listed; the record type's own subset still applies, so both must allow the value. The refusal says "This value does not go with the chosen value of <field>." and names no value.

**Records that already hold an old pair are never blocked by an edit of something else**, and sending the same pair back changes nothing. Only a write that changes the dependent value or the other picklist's value is judged. Making an existing field dependent over existing records is allowed: the check of the change set says "N records hold a pair in <object>.<field> that is no longer allowed" (a count, never a value), and the records stay as they are until someone changes one of the two fields. Everything works in a change set, with preview, publish and rollback, because it is the ordinary change of a field.

### 26.2 A picklist whose list the organization owns

A platform object can have picklists flagged `organizationValues` (the shipped object of the Audit maintenance solution has two). The platform owns the field (name, type, the other picklist it depends on); **the organization's administrator owns the list of values and the pairs**: in the object manager the field's page has **Edit the values**. Only the values, their labels, whether each is offered, and the pairs can be changed; the form shows the rest and the server refuses anything else. A value is never removed, only switched off; a rollback that would remove a value you added is refused, as for every picklist. Another organization never sees your list. A platform release can replace the *starting* list for organizations that never changed theirs.
A field flagged `codeValues` takes only short codes as values (1 to 10 letters, digits, hyphens or underscores): the label says more.

**Adding one value while filling in a record.** A member who holds the ability **Add values to picklists** (`field-values.add`, which the Organization administrator profile has) and may edit the field sees **Add a value** beside it. They give a code, optionally what people read, and (for a dependent picklist) tick which values of the other picklist it goes with. It takes effect at once, is audited by counts and cannot rename, switch off or remove a value. To let the authors of templates do this, give their profile (or an individual grant) that ability.

### 26.3 A field only the platform writes

A field flagged `platformWritten` (a text field, not required, no default) cannot be set by anyone through the API, a bulk change or an import, not even by the administrator; the form shows it as read only. Only the platform's own write sets it (a later sprint's engine), and a workflow, an approval outcome or a before-save automation cannot be made to set it.

### 26.4 Number series (for contributors)

A solution declares its series in `solutions/<key>/numbering.yml`: a `key`, the `object`, the `field` that holds the number, and either one `format` or a `selector` (a short-code picklist) with a `format` for each of its values. The format language is closed: fixed text from `[A-Za-z0-9/_-]` (10 characters in all), `{field:<apiName>}` (a short-code picklist or text field of the record whose value is `[A-Za-z0-9_-]{1,10}`), exactly one `{seq:N}` (the counter, 1 to 9 digits, never cut) and at most four parts in braces. Example: `WI/{field:subCategory}/{seq:2}` gives `WI/PRD/03`. The counter is one per **scope** (the selector's value and the values of the fields the format reads): `WI/PRD` and `WI/QLT` count apart, a scope that was never numbered starts at 1, every organization has its own counters, and a number is never issued twice (a rolled-back number was never issued). A mistake in the file stops the application from starting with a message that names the series and the part.
The code takes a number with `NumberSeries.next(seriesKey, values)` inside a transaction and writes it with `BackgroundWrites`; nothing takes a number through an endpoint or a screen in this release. `prefix(...)` gives the fixed part only ("WI/PRD/"), for a preview.

**Change the starting list or the pairs of a shipped picklist.** Edit its `settings.values` and `settings.dependency` in the object file. It is data: the baseline records only that the list is the organization's and which field it depends on. Organizations that never changed their list get the new one.
