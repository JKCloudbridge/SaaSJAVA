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

A platform object can have picklists flagged `organizationValues` (the shipped object of the Quality Management System solution has two). The platform owns the field (name, type, the other picklist it depends on); **the organization's administrator owns the list of values and the pairs**: in the object manager the field's page has **Edit the values**. Only the values, their labels, whether each is offered, and the pairs can be changed; the form shows the rest and the server refuses anything else. A value is never removed, only switched off; a rollback that would remove a value you added is refused, as for every picklist. Another organization never sees your list. A platform release can replace the *starting* list for organizations that never changed theirs.
A field flagged `codeValues` takes only short codes as values (1 to 10 letters, digits, hyphens or underscores): the label says more.

**Adding one value while filling in a record.** A member who holds the ability **Add values to picklists** (`field-values.add`, which the Organization administrator profile has) and may edit the field sees **Add a value** beside it. They give a code, optionally what people read, and (for a dependent picklist) tick which values of the other picklist it goes with. It takes effect at once, is audited by counts and cannot rename, switch off or remove a value. To let the authors of templates do this, give their profile (or an individual grant) that ability.

### 26.3 A field only the platform writes

A field flagged `platformWritten` (a text field, not required, no default) cannot be set by anyone through the API, a bulk change or an import, not even by the administrator; the form shows it as read only. Only the platform's own write sets it (a later sprint's engine), and a workflow, an approval outcome or a before-save automation cannot be made to set it.

### 26.4 Number series (for contributors)

A solution declares its series in `solutions/<key>/numbering.yml`: a `key`, the `object`, the `field` that holds the number, and either one `format` or a `selector` (a short-code picklist) with a `format` for each of its values. The format language is closed: fixed text from `[A-Za-z0-9/_-]` (10 characters in all), `{field:<apiName>}` (a short-code picklist or text field of the record whose value is `[A-Za-z0-9_-]{1,10}`), exactly one `{seq:N}` (the counter, 1 to 9 digits, never cut) and at most four parts in braces. Example: `WI/{field:subCategory}/{seq:2}` gives `WI/PRD/03`. The counter is one per **scope** (the selector's value and the values of the fields the format reads): `WI/PRD` and `WI/QLT` count apart, a scope that was never numbered starts at 1, every organization has its own counters, and a number is never issued twice (a rolled-back number was never issued). A mistake in the file stops the application from starting with a message that names the series and the part.
The code takes a number with `NumberSeries.next(seriesKey, values)` inside a transaction and writes it with `BackgroundWrites`; nothing takes a number through an endpoint or a screen in this release. `prefix(...)` gives the fixed part only ("WI/PRD/"), for a preview.

**Change the starting list or the pairs of a shipped picklist.** Edit its `settings.values` and `settings.dependency` in the object file. It is data: the baseline records only that the list is the organization's and which field it depends on. Organizations that never changed their list get the new one.

## 27. Sheets, versions and the template page (Functional track FA3)

> **Since FA7 the screens say *Revision*, written with two digits starting at 00.** Where sections 27 to 38 of this guide say "version" of a sheet
> or a template, read *Revision*: the first one is Revision 00, the next 01 (see section 39). The numbers the guide gives for the stored
> versions and the API are unchanged.

Decisions: [ADR-0186](adr/0186-the-sheets-module-versions-the-owner-seam-and-the-request-size.md), [ADR-0172](adr/0172-the-grid-component-and-the-adapter.md), [ADR-0173](adr/0173-the-canonical-grid-json-and-its-limits.md), [ADR-0174](adr/0174-template-versions-and-approval-together.md). For the administrator of an organization that has the Audit maintenance feature, and for the contributor who adds another sheet owner.

### 27.1 What a sheet is (for administrators)

A **sheet** is a spreadsheet-like grid that belongs to one record: in this release, one **template**. It has named **columns** of five kinds (text, number, date, one value of a list, yes or no), **header rows** (a title over several columns), and either **rows the sheet names** or **rows the filler adds**. A cell is either **fixed text** (a label, an instruction, a limit) or a **cell the filler types into**; a header cell is always fixed. Fixed text can be bold, italic and one of four sizes (small, normal, large, title), never a size in pixels. Cells can be merged. A sheet is up to **1,000,000 bytes**, **500 rows by 100 columns** (the limits are settings, see 27.5), and what a sheet does not say is not stored, so a large empty sheet is small.

Creating a template creates **no table** and no object: the sheet is kept as numbered **versions** of the template.

### 27.2 Building and saving (Quality Management System, Document Control)

1. Open **Templates**, choose **New template**, fill in the header and save; open the template. A person who may only read sees the list and the template, not New.
2. The template has no version yet: choose **how the rows come about** (the sheet names them, or the filler adds them) and **Start the first version**. That choice is made once. It can be changed only while the first version is the only one and is still a draft.
3. Build the sheet in the **Form view** (the columns: label, what the column holds, the value a new row starts with, whether the filler must fill it, the values of a list; the header rows; the rows) or in the **Grid view** (type to write fixed text, Enter to edit, Delete to clear, Ctrl+B and Ctrl+I for bold and italic, the size menu, **Merge** and **Unmerge**, **Fixed text** and **Input cell**, **Undo** and **Redo**, paste a block copied from a spreadsheet application). A cell with no text is a cell the filler types into. Only fixed text can carry a style; to style an empty cell, type its text first.
4. **Save.** The save carries the version of the draft that was read: if someone else saved first, nothing is changed and the page offers to reload (your unsaved changes are lost). A refusal names the place that is wrong ("Column 2, default") in the server's words.
5. On a phone the grid is for reading; use the Form view to build.

### 27.3 Versions (what exists in this release)

Every save changes the **draft**. There is at most one draft at a time; **New version** is offered from any version when there is no draft, and copies it. Numbers are automatic and never reused. Nothing is ever deleted: older versions stay and can be opened (read-only) from the list, tagged **Draft**, **In review**, **Approved**, **Active** (the version in use), **Replaced**, **Rejected**, **Withdrawn** or **Retired**. A person who may only read the template sees the versions that are or were in use. **In this release only drafts are made and changed: submitting for approval, approving, publishing and retiring arrive in the next sprints**, and the database already refuses every other change.

### 27.3a Start a version from a spreadsheet file

You do not have to type a sheet that already exists in a spreadsheet. On the **Sheet** tab choose **Upload a spreadsheet** (or **Start from a spreadsheet** on a template that has no version yet) and pick a file saved as **.xlsx**, or a text file saved as **.csv** in UTF-8. A window tells you what the file gives before anything is saved: how many columns and rows, how many merged areas, what was left out and what came in another form. The box **The first row holds the names of the columns** is ticked by default; untick it and the columns are called Column 1, Column 2 and so on, and the first row becomes a row of the sheet. **Save as a new draft** makes the draft with the content in it; you can then change it like any draft.

What comes in: the first sheet of the workbook; every text as fixed text of the sheet (it is the sheet's own text, not something the person who fills it in can change); empty cells as cells the person who fills it in types into; column widths, row heights, bold, italic, the four sizes and merged areas where the file has them. A formula comes in as the value it last showed, a date as year-month-day. What is left out, and counted for you: images, charts, other sheets, links to other files, cells that showed an error, and merged areas that cannot be kept.

When it is refused, the window says why and where ("Row 12, column C has a text longer than the allowed 1000 characters."); nothing is cut and nothing is saved. A file with macros, an older .xls file, a file protected by a password, a text file that is not UTF-8 and a file over the size limit are refused with the way to fix them. A draft that exists refuses the upload and tells you its number: continue with it, or send it for approval first. On a sheet whose filler adds the rows, only the names of the columns come in. The file itself is not kept.

#### What comes in and what does not (a form with boxes and a title)

A spreadsheet that is a form (boxes to write in, a title across the page) now comes in as it looks.

- **A cell with a border is part of the table, even when it has no text.** One line on any side is enough (a line below an answer, a box). Such a cell becomes a cell the person who fills the sheet in types into, and the sheet is as wide and as tall as the boxes reach. A cell with **no** border is not part of the table: the empty cells to the right of or below the table stay out. The window says how many blank cells with a border were kept.
- **A merged area with a border keeps its whole area** (a merged answer area of three columns and five rows comes in as that merge).
- **A title across the page.** When the first row is one merged cell across the whole width of the table, the window says "The first row looks like a title" and the box **The first row holds the names of the columns** starts **unticked**. The title is the first row of the sheet (its text, bold and size, and its merge), and the columns are called Column 1, Column 2 and so on. Tick the box if you do want that row to name the columns. A merged first row over only part of the width is an ordinary row. A text file (.csv) has no merges and is never a title.
- **If the whole page is boxed** (thousands of empty boxes), the file is refused with the limit and its number (500 rows, 100 columns, 200,000 cells read); nothing is cut. Remove the boxes from the unused area in the spreadsheet and upload again.
- **Not read:** a border you gave to a whole row or a whole column in one step may be stored as a style of that row, not as cells; select the table itself and give it the border. Border colour and thickness are not kept.

#### When the new sheet format is on: the look of the file comes in (SH1B)

When the administrator has switched on the new sheet format, a workbook comes in **as it looks**, as a draft revision in format version 2. (With the switch off nothing here applies: the upload is what it was above.)

- **Which worksheet.** The window lists the worksheets you can see, by their names, with the size the file says each has, and reads the first one that holds anything. Choose another and the window reads that one again. A template holds **one** sheet for now, so a workbook with several visible worksheets becomes several templates: upload the same file into each template and choose a different worksheet each time. Hidden worksheets are skipped and counted.
- **What comes in:** text; column widths and row heights as they are (narrower columns and shorter rows than before are allowed); merged areas; the font, mapped to the nearest of sixteen fonts the platform draws (a font it does not know is drawn in a similar standard one, and the window says how many); the size; bold, italic and underline; the text colour and the fill colour exactly (theme colours and tints are worked out); the four borders in their six styles; the number format (percent, two decimals, thousands, month-year, short date, accounting); wrapping and alignment; the frozen rows and columns; the paper size, the direction and "fit to the width of the page"; a link to a secure web address on a cell with text; and a rule that paints a cell when its value is above, below or equal to a number (up to 50).
- **Check boxes** become yes/no cells. A Yes box beside a No box is one yes/no cell. A box that stands on a cell with text does not replace the text and is listed as not placed; so is a box that stands outside the table.
- **Counted and left out:** other kinds of conditional colour, links to other places or to other files, shapes and text boxes, embedded files (the window lists the cells where they stood, so you can set those cells to take attachments when you edit the sheet), pictures, charts, pivot tables, comments, names, and other worksheets. A formula comes in as its last value. A formula longer than 4,000 characters or nested more than 32 deep refuses the file, with the row and column.
- **A number format the sheet does not have** (for example a unit or a currency sign) keeps the number as a plain number and the window counts it.
- **Nothing you see here draws the look yet.** The screen that draws fonts, colours and borders comes in a later release; until then the grid shows text as before and the look is stored, ready.

### 27.4 For contributors: another owner of a sheet

Name the object in `sheetOwners` in the `solution.yml` of the solution that brings it (it must be one of its `objects`); the page passes `{object, id}` to `SheetWorkspace`. Nothing else in `sheets` or in `components/sheets` changes, and no cluster word may enter them (`SolutionBoundaryTest`). Who may read or change the sheet is the owner record's own answer (read, update): never write a second rule. To change the **format** of the grid: change `platform-api-contract/src/main/resources/schemas/sheet-grid.json`, the validator and its corpus (`src/test/resources/sheets/hostile-grids.txt`), regenerate the OpenAPI document and the client, and decide whether it needs a new `formatVersion` (a new reader plus a migration of stored versions, never an in-place change).

### 27.5 Settings

`platform.sheets.limits.max-bytes` (1,000,000), `max-rows` (500), `max-columns` (100), `max-header-rows` (10), `max-cell-chars` (1,000), `max-label-chars` (200), `max-picklist-values` (100), `max-merges` (2,000), `max-depth` (12), `max-versions-per-owner` (1,000); `platform.sheets.small-body-bytes` (4,096); the upload of a spreadsheet file (UI3B): `platform.sheets.imports.max-file-bytes` (5,000,000), `max-part-bytes` (20,000,000), `max-inflated-bytes` (100,000,000), `max-entries` (100), `max-cells` (200,000), `max-concurrent` (4). The database refuses a grid above 5,000,000 bytes whatever `max-bytes` says. The proxy in front of the application should set a request size limit too (the application bounds a body with no announced length, the proxy bounds the connection); for the upload path it must allow at least `max-file-bytes`.

## 28. A step that is skipped when nobody can decide it, and what a business module can do with an approval (Functional track FA4A)

Decisions in ADR-0187. This section is for an administrator (the first part) and for a contributor (the second).

### For an administrator: an optional step

On the step form of an approval process (**Setup, Approvals**, open a process, a step) there is a box, **"Skip this step when nobody can decide it"**. It is off for every step that exists and for every new step.

- **What it does.** When a step is reached, the platform works out who is asked (the rules of the step; if they name nobody who can decide, the fallback approver of the process). If nobody is left **and the box is ticked**, the step is skipped and the request goes on to the next one. The history of the request shows "Step skipped"; no notice is sent about it and no reminder ever starts for it.
- **What it does not do.** The fallback approver is always asked first. A step without the box behaves as it always did: if it is the first step, the record cannot be submitted; if it is a later step, the request waits and an administrator is told ("nobody can decide").
- **The last steps.** If every step after an approval is skipped, the request is **approved** at that moment, exactly as if the last approver had approved: the approved outcome is applied and the person who submitted is told.
- **Nobody at all.** If, when a record is submitted, nobody can be asked for any step (or an unmarked step that must be decided has nobody), the record cannot be submitted, with the same words as before.
- **Example.** A process with a *Reviewer* step (box off) and an *Approver* step (box ticked). The person who submits names a reviewer but no approver. The reviewer approves; the second step has nobody, so it is skipped; the request is approved.
- **Trying it.** "Work it out" on the process page says, for a record, which step would be skipped.
- **A process saved before this existed** has the box off on every step.

### For a contributor: hooks

A module that owns an object can react to its approvals by implementing `ApprovalHooks` (see the engineering guide, "Approval hooks and the step that is skipped"). In short: one hook per object; `onSubmit` may refuse before the request exists; `beforeApprove` may refuse and may write the fields it declared in `writes()` through the writer it is handed, while the record is locked by the very request that is being decided; `onEnded` is told once how the request ended. Everything happens in the transaction of the decision, so a failure undoes the decision, the hook's writes and any counter it took. A hook is handed facts, never a comment or a value.

**To add a hook:** implement `ApprovalHooks` as a bean in your module (it may depend on `data`, `metadata` and the shared kernel, never the other way round); declare `writes()` with fields that only the platform writes (`platformWritten` in the object file, ADR-0183); keep each call short; test it with a test configuration that registers a hook for a test object, as `ApprovalTestHooks` does, so the neighbouring tests run without it.

## 29. The lifecycle of a template: the approval process, the number, publishing and retiring (Functional track FA4)

For the organization's administrator. The decisions are in ADR-0188 and ADR-0189.

**1. Build the approval process for Template** (Setup, Approvals, new process on Template). Name the approver of each step **by role** (kind Role) or by group; whoever holds the role approves. Do not add "reviewer" or "approver" fields to Template: there are none. A step may be marked "Skip this step when nobody can decide it" (section 28). Entry criteria are not needed. The first approver of the first step who has somebody gives the number, whichever step that is. **An approver must be a licensed member** or the step has nobody. Give every person who approves the **Approver** policy too, or they will not see the template when they are asked to approve (a person with an open task can still read the version they are asked about, and nothing else).

**2. Give the policies.** The policies come with the application and cannot be changed. An access policy is given to a **person or a group** in Access policies; it cannot be given to a role. A role decides which records a person can see (the role hierarchy) and can be named as the approver of a step, but it does not carry an access policy:

| Policy | What it allows |
|---|---|
| **Author** | make templates, choose their Level and Document, change their own, build the sheet of a draft, send it for approval (the identity category and sub-category are the approvers') |
| **Approver** | see every template; set the identity category, sub-category, part code and plan year, and correct a template or its sheet, while it is in review (recorded in the audit trail); add a value to a picklist from the form; start a draft. It does **not** publish, unpublish or retire (the Publisher policy does) |
| **Publisher** | read every template; publish an approved version to departments, take it back, retire it (give it to the group that has the last word, see section 37) |
| **Reader** | read the templates published to them |
| **Filler** | read the templates published to them (fill-ins come later) |
| **Department setup** | keep the list of Department groups |

Give **Author** to the people (or a group of them) who write templates and **Approver** to the people or groups who approve, and **Publisher** to the people or groups who publish (section 37). A person who is only to read needs **Reader**, and must be in a department's group to be published to.

**3. The number.** The number is made when the first approver approves: `IMS/01`, `QSP-01`, `WI/PRD/01` and so on, by the category (the formats are in `numbering.yml`, section 26.4). Four categories end in what the author types: a **control plan**, a **process flow chart** and a **failure mode analysis** end in the **part code** (`CP/PRD/AB-12`), a **business plan** in the **plan year** (`BP/2026`). The author does not fill these in: the people who approve first decide the **identity category and sub-category** (and the part code or plan year where the number needs one) while the template is in review, and the first approval says in words what is still missing (section 34). The category, sub-category, part code and plan year of a template that already has its number are not locked by the platform: do not change them, because the number does not follow.

**4. Publishing is a separate act from approving.** An approved version is visible to the author and the approvers only. **Publish** (a person with the Approver policy) makes it visible to the people of the departments chosen. A template can be published while another version of it is in review. **Unpublish** takes it back from the people it was published to: exactly the access the publish gave is removed, and a share somebody gave by hand with the share panel stays. A published version that is replaced by a newer one becomes "Replaced" and stays readable as history.

**5. Retire** ends a template for good. It needs no pending approval (recall it or let it finish). Nothing is deleted; the people who had it still read it; the number is never given to another template. A retired template cannot be sent for approval again. Nobody can mark a template retired by editing it: only the Retire button does.

**6. Who sees what.** A template is private: its owner (the author), the people who hold the Approver policy (they view every template), and the people it is shared with or published to. The role hierarchy adds nothing for templates, and the sharing setup shows the object as fixed. You can still share one template by hand with a person, a group or a role (the share panel); that gives read access to the template, never to a draft version.

## 30. Department groups: which group is a department (Functional track FA4)

For the organization's administrator. The decisions are in ADR-0190.

A template is published to a **Location** and a **Department**, and the people who see it are **a group you keep**. The place that says which group belongs to which pair is **Quality Management System, Department groups** (the policy **Department setup** shows it).

1. **The lists.** Location and Department are lists you own, like the categories (section 26). A starting list of placeholders comes with the platform; replace it with yours (Setup, Objects, Department group, the Location and Department fields). A department belongs to the locations you list it under; the department list narrows to the location when you choose.
2. **The groups.** Make one **public group** for each department (Setup, Groups) and add its people. Nested groups count. The group is yours: add and remove people there, and the next page they open shows or hides the templates.
3. **The mapping.** Add one **Department group** record for each pair: choose the Location, then the Department, then the Group. **One record for each pair**: if two exist for the same pair the publish refuses and says so.
4. **What a publish says.** "No group is set up for this department yet" (no record), "More than one group is set up..." (two records), "The group set up for this department no longer exists" (the group was removed; fix the record), "The group set up for this department has no members yet" (the group is empty). A publish to several departments is all or nothing: the first pair that cannot be published to stops it and the dialog names it.
5. **A removed group** takes its access with it. The template's page says that the group no longer exists; correct the record, then publish again.
6. **The people** of a department are exactly the members of its group: nothing is read from a person's profile, and nobody is mailed when a template is published to them.

## 31. Change requests on a published template (Functional track FA5)

For the organization's administrator and for the contributor. The decisions are in ADR-0191.

**What a person can do.** A person who can read a template that is in use (a Reader or Filler it was published to, but also an author or an approver) opens the template in Quality Management System, Document Control. Under the sheet there is **Ask for a change**. They stand on a place in the grid (a click on a cell, a click on a column label, or the arrow keys), choose whether the comment is about **this cell, this row, this column or the whole template**, write it, add it to the request, give the request a title and send it. They cannot change the sheet and the request changes nothing in it.

**Who sees a request.** The person who asked, the owner of the template, and everybody who holds the **Approver** policy (they can change every template). Another reader of the same template sees none of them; an author who does not own the template sees none of them. If the person who asked can no longer read the template (it was taken back from their department), they no longer see their request; the owner still does.

**What the owner does.** Under **Change requests** the owner sees each request with its comments, where each one points, and which of those places are still in the version in use (a comment on a row that a later version removed says **no longer in this version**, with the name the row had). The owner can add a comment, **accept** an open request, **decline** it (a reason is needed and the person who asked reads it), or choose requests and **turn them into a new version**: that makes a draft which is a copy of the version **in use now** (so nothing already approved is lost) and links the requests to it. If a draft already exists, the requests are linked to that draft and it is left as it is. The owner edits the draft in the sheet and sends it for approval like any other version. If that version is rejected or withdrawn, the comments stay with the request and the owner can **reopen** it.

**You need to set up nothing.** There is no new policy, ability or setting to give: asking is the right to read the template, answering is the right to change it. People are told by a notice (the bell): the owner when a request arrives or the person who asked comments, the person who asked when someone else comments or the request is accepted, declined or turned into a version. A person can switch these notices off in their notification preferences (**Change requests**); no mail is sent for them.

**Limits** (settings `platform.inspection.change-requests.*`): 50 comments in a request, 10 open requests for one person on one template, 200 requests for one template, 2,000 characters in a comment, 120 in a title, 500 in a reason. Nothing is ever deleted: a request and what was said on it are kept. A text may not contain markup (the characters < and >).

**For a contributor.** (Annotate mode and the places were removed from the screens in FA10, ADR-0204; the server still accepts places.) Annotate mode of the grid was generic: `SheetWorkspace` takes an optional `annotate` (`lib/sheets/annotate.ts`) for the one version it names, which must be read-only and not a draft; the grid stays read-only, marks the places that have notes and asks for the box of a note on Enter. The panel and the list are the cluster's (`solutions/inspection/AnnotatePanel.tsx`, `ChangeRequestsSection.tsx`). The backend is `app.platform.inspection.internal` (`ChangeRequests` the rules, `ChangeRequestAccess` the one place that decides who sees what, `ChangeRequestStore` the only class that names the tables) and the generic `SheetLifecycle.startDraft`, `statusOf` and `layoutOf`. The notice kind is `CHANGE_REQUEST` in the category `REQUESTS`.

## 32. Fill-ins: filling in a published template (Functional track FF1)

For the organization's administrator and for the contributor. The decisions are in [ADR-0192](adr/0192-fill-ins-the-header-record-the-cell-table-and-review-by-the-approval-engine.md).

**What a person does.** A person who holds the **Filler** policy and is in a department a template was published to opens the template (Quality Management System, Document Control) and presses **Start a fill-in**. The fill-in is made on the version of the template that is in use at that moment and stays on it for ever, even when a newer version is published later. They type into the cells the template marks as input (fixed text from the template cannot be typed into), in the grid on a wide screen or in the form view on a phone, add rows where the template lets the filler add them, and press **Save**. A half-done fill-in can be saved. When it is complete they press **Submit for approval** in the approval panel of the fill-in; only what is saved is sent. The values of each column are checked by the type of the column (text, number as the digits typed, a real date, one of the listed values, yes or no); a required column needs a value in every row.

**Review.** The organization's administrator builds an approval process on **Fill-in** in the approval setup (`/setup/approvals`), with steps that name people, roles or groups, exactly as for a template. **Without a process, Submit sends the fill-in for good** (section 34): it is stored, read-only, with the status Submitted, and a process made later does not reopen it. When a step approves the last time the fill-in is **approved** and fixed. When a step **rejects** it (a comment is required) the fill-in is **returned**: it goes back to its filler, who changes it and sends it again (the history keeps both requests). The filler can **recall** a fill-in before it is decided and has a draft again. A person who is asked to review a fill-in but holds no policy of the solution reads that one fill-in and nothing else, and decides it from their inbox.

**Who sees which.** A filler sees their own fill-ins. The people who hold the **Approver** policy and the new **Reviewer** policy see every fill-in of the organization (the platform has no way to say "the fill-ins of this template" in a record rule, so it is all of them); a Reviewer can read and change none. Other fillers do not see each other's. Nobody can delete a fill-in, not even an administrator.

**Editing after it was sent** (business decision 11). Only a person who holds the **Approver** policy (the one that edits every template and passes the lock of an approval) can change the cells of a fill-in that was sent or approved. Every such correction is counted and recorded in the audit trail with its size and a fingerprint, never its content, and, while the review is pending, in the history of the request. A correction cannot take a required value away.

**After the template moves on.** A fill-in stays readable whatever happens to the template. If a newer version is published, or the template is taken back from the people, a draft can still be finished and sent. If the template is **retired**, its fill-ins stay readable but can no longer be changed or sent, and no new one can be started.

**Policies.** The **Filler** policy now holds the ability *Start fill-ins of sheets* (`sheets.fill`) and read and update on the fill-in object with no field to edit; the **Approver** policy holds view all and modify all on it; **Reviewer** holds view all only. The header record of a fill-in is made and kept up to date by the platform only: a person cannot create, rename or move one through the record screens (every field is one only the platform writes).

**Settings.** `platform.sheets.fills.max-bytes` (1,000,000), `max-rows` (1,000), `max-depth` (6); the length of a text is the template's own limit. The database holds its own ceilings (5,000,000 bytes, 10,000 rows).

**For a contributor.**
- *Another owner of fill-ins.* The sheets module is generic: a module that owns a header record calls `FillLifecycle` (`start`, `submit`, `ended`) in its own transaction and makes the header as the platform. The cells are read and saved through `/api/v1/sheet-fills/{object}/{id}` (and `/cells?version=`); who may read or change them is `FillOwners`, nowhere else.
- *The shape of the cells* is shape B of ADR-0173 section 6: `{"formatVersion":1,"rows":[{"id","cells":{"<columnId>":"value"|true|false}}]}`, sparse and canonical. A different shape later is a new reader and a migration of stored fill-ins, decided with the real filled example (open item B).
- *The status* is the cell table's (`DRAFT`, `SUBMITTED`, `RETURNED`, `APPROVED`, moves guarded by V069); the header carries a copy, written together with every move by `FillHeaders` (the cluster's only other use of the platform's door to records).
- *Not built, on purpose (FF2):* attachments, reportable columns and reports, partitions, archiving old fill-ins to the object store, a number for each fill-in.

## 33. Reports on fill-ins and the archive (FF2)

**Mark a column reportable.** In a new version of a template, open a column and tick "Can be used in a report". At most 20 columns of a version can be ticked. Only fill-ins started on a version where the box was ticked can be found by the report on that column; older versions and their fill-ins are not changed.

**Ask the report.** Open the "Report" page of the application, choose a template, a column, a comparison and a value. You see the fill-ins you are allowed to see, as in the list. A page can be short and still have "Show more".

**Attachments.** A fill-in's files are added on its page. The person who started a draft or returned fill-in adds and removes them; after it was sent, only people who may correct it do, and each such change is recorded.

**The archive.** Approved fill-ins older than the archive age (setting `platform.sheets.archive.age`, default 365 days) are moved out of the database to the object store. They stay in the list, are still found by the report, open as before and are read-only. Nothing is deleted.

## 34. Level and Document, the identity pair, the department of a request and Submit (Functional track FA6A)

For the organization's administrator. The decision is in [ADR-0196](adr/0196-the-identity-pair-at-the-first-approval-level-and-document-the-department-of-a-request-and-submit-without-a-process.md).

**Level and Document (what the author chooses).** A template has two fields the person who writes it chooses: **Level** and, depending on it, **Document**. They carry **no number**: they only say what kind of document it is. You keep the lists and which documents go with which level in Setup, in Object settings, Template, Fields, the same way as for the other pairs: open the field, **Edit the values**, and for the Document field map each level to its documents (Level 1 → Document A, Document B; Level 2 → Document C). The platform ships a short list of placeholders (two levels, four documents) for you to replace. A person who picks Level 2 sees only the documents you mapped to it. A template that already exists with no level is simply unchanged.

**The identity pair (what the approvers decide).** The fields are **Identity category** and **Identity sub-category**. The author does not choose them. The people who approve open **Edit**, choose the pair and any typed parts the format needs, then **Save**. From FA6C, that Save gives the real number at once. They may change the identity and save again until the first **Approve**, which fixes it. An incomplete identity can stay unnumbered; approval with missing parts is refused in words and the review stays open. See section 36 for counters and rejection.

**Why an approver can edit a template in review.** A pending approval locks the template for everyone. The Approver policy holds the ability *Change a record while it is being approved* (`approvals.override-lock`), which passes the lock; every such change is recorded in the audit trail and in the history of the request. The limit to know about: the ability works for any template in review, not only the one a person was asked about. The author does not hold it, so they cannot change the template while it is in review; if it is rejected it goes back to them and they can change what their policy allows.

**Any number of steps.** Save normally gives the number. The first actual Approve fixes it, whichever step comes first. If another route left complete identity fields without a number, that approval still gives one as a backstop. More steps, different people and skipped steps do not require a code change.

**Adding a missing sub-category.** The Approver policy holds *Add values to picklists* (`field-values.add`): a person who approves can press "Add a value" in the form for the identity sub-category (or any list the organization owns), and the value lands in the same list as Setup shows. (A demo organization that made its own policy only for that ability can remove it.)

**Publish: several departments at once.** In the Publish dialog choose a **Location**, tick the **departments** of it, press **Add the ticked departments**, choose another location and tick again if needed, then press **Publish** once. A department already in the list is shown ticked and cannot be ticked again; remove it from the list to take it out.

**Who asked, from which department.** A change request shows the name of the person who asked and the **department they were in when they asked** (taken from the Department group records and the groups the person is in, nested groups included, and kept as it was: a later move of the person does not change it). A person in no department group shows a dash; a person in several shows all of them, separated by semicolons.

**Submit on a fill-in.** The person who fills in presses **Submit** (it saves what was typed first, then sends it) or **Cancel** (nothing is sent; what was saved stays a draft in their list). If the organization has an approval process on **Fill-in**, Submit opens the usual "Submit for approval" popup and the fill-in is reviewed as before. If it has none, Submit checks the fill-in (every required value, and a row where the sheet needs one) and stores it with the status **Submitted**; it is then read-only for everyone except the counted correction. **A fill-in submitted before a process existed stays final**: making a process later does not review it.

**For a contributor.** `ApprovalApplicability` (shared kernel) says whether a process applies to a record; `FillSubmission` (sheets) sends a fill-in with no review behind it and only the cluster may call it (an architecture test); the numbering checks are in `TemplateApprovalHooks.beforeApprove`; the snapshot is `DepartmentAudiences.departmentsOf` and the column `inspection_change_request.requester_department` (V072). Test fixtures: `TemplateSupport.author` gives the test person the identity fields too, `plainAuthor` is the real Author policy.

## 35. Numbering set up by the organization (FA6B)

Open **Setup -> Numbering** as the organization administrator, or as a member explicitly given **Manage numbering** (`numbering.manage`). Managing objects and fields alone does not grant this ability. The list shows the categories, their formats, the source of a format and its minimum digit width. Switched-off categories remain listed and their earlier numbers are retained.

Sub-category is optional, including when choices exist. If a format reads it and it is blank, Save and Approve use the without-sub-category default (shipped as category/count). Choosing a value uses the normal category format; clearing it before first approval takes the next category-only number. Existing issued numbers stay reserved. **Use the platform's format** ends your own format for later allocations; fixed numbers and existing counters are retained.

Open a row, edit the format and use **Preview**. Choose the parts the server asks for and preview again, then **Save format**. Fixed text, field parts such as `{field:category}` and `{field:subCategory}`, and a count such as `{seq:1}` form the closed language. `{seq:2}` writes 5 as 05; a count that grows beyond the minimum width is never cut. The four shipped formats made of typed parts without a counter still work.

The two default rows start as category/sub-category/count and category/count when the optional sub-category is absent, with width 1. The organization may change these defaults. A category-specific organization format wins over its shipped format; shipped category formats win over defaults. Exception: when that format reads a blank sub-category, the without-sub-category default applies. Adding a valid new category or sub-category in Setup or through the permitted form control needs no release to obtain a default number.

**A format change affects future allocations only.** Fixed numbers are never rewritten. The same fields continue their counter even when fixed text, field order or width changes. Different fields count separately; returning to an earlier field set resumes its counter. A spelling already issued to another record is never reused; counting skips retained spellings and typed collisions are refused. Before first Approve, a template's identity edit may replace its provisional number as explained below.

**A preview does not reserve a number.** Another Save or approval backstop may take the observed count. To continue from an earlier system, preview a category's saved format and raise **Next count**, for example to 37. The next available allocation starts there. A count can only go up, never down; a format without a counter has no count to raise.

For contributors: the file `numbering.yml` defines the platform's starting formats and generic defaults. An administrator's format changes are data in `numbering_format` (V073), not edits to the shipped file or a new release. `NumberingFormats` resolves settings afresh under a shared transaction lock; saves and advances take the lock exclusively. `NumberSeries.next` remains the only allocation door. Audit carries the action, series, setting kind and number of parts, never the format, codes or number. Decision: [ADR-0197](adr/0197-organization-number-formats-previews-and-counter-continuity.md).

## 36. Numbering at Save and fill-in changes (FA6C)

**Save, then Approve.** A person allowed to set the identity gets a real number when they save complete numbering parts.
An author leaving those fields empty gets none. Before the first Approve, changing a part the current format reads
takes another number; changing only the name or an unused part keeps the number. If required parts are cleared, the
provisional number disappears from the header until they are complete again. Earlier numbers stay used.

**Switching back does not recycle a count.** If you save WI + PRD, change to WI + QLT, then return to PRD, an otherwise
unused set of counters gives WI/PRD/01, WI/QLT/01, then WI/PRD/02. The earlier PRD/01 belongs permanently to that template
and nobody else can receive it. A typed number without a running count can be reused by the same template. Another
template is refused. Gaps are expected and a next count is never lowered.

**First Approve fixes it.** Category, sub-category, part code and plan year then become read-only. A rejected template
keeps its number. Rejection before any approval leaves its identity editable by whoever has the relevant rights;
rejection after one approval keeps it fixed. Numbers issued before FA6C are also fixed.

**Why a selected sub-category may not be printed.** Adding a value changes the organization's list, not the numbering
format. The current shipped formats, also observed in the local organization's Setup before this change, divide as follows:

| Categories | Printed parts and count |
|---|---|
| IMS, QSP, CORPQSP, ESSP | One count per category. Sub-category is not printed and does not split the counter. |
| WI, SOP, F, FEHS, CSF, PPT, L, LEHS | The selected sub-category is printed; each category/sub-category pair counts separately. |
| CP, PFC, FMEA | Sub-category and typed part code; no running count. |
| BP | Typed plan year; no running count. |

An administrator can override a category in **Setup -> Numbering**. For QSP/HERO/1 then QSP/HERO/2, save the category
format `{field:category}/{field:subCategory}/{seq:1}`. This is an example to choose deliberately, not an automatic
change made by FA6C. Organization overrides may change the table above; inspect the effective format and its source.
The platform stores short codes in uppercase, so a label such as Hero can print as HERO.

**A value stays available.** Add value selects the returned code. In Setup -> Objects -> Template -> sub-category ->
Edit the values, the value remains ticked against the category sent in `controllingValues`. Other permitted people see
it on their next form, and the next template using that pair continues its counter when the format reads it. Adding the
same code twice reuses the value and creates no second counter.

**Fill-ins save after a short pause.** Draft and returned fill-ins autosave about two seconds after the last valid edit.
Save stays available; the title band shows Saving/Saved. A refused save keeps the work and stops automatic retries.
Read the explanation, then use Save, or for a conflict **Reload and reapply my changes**. Review that merged result.
Submitted, approved and archived fill-ins never autosave. Submit still ends a fill-in without review when no applicable
fill-in approval process exists.

Bold, Italic, Underline, four text sizes, Left/Centre/Right alignment and Default/Accent/Green/Red text colour apply
to editable input cells. The same controls work on fixed text in template drafts. Reset formatting returns to the published template's style.
The rest of the strip stays visible; a disabled structural tool explains that the published template owns it. Old fill-ins
remain readable. Formatting travels with the saved fill-in and archive, and does not change report values.

The bell opens a searchable table. A notice opens its readable template in Templates; unavailable targets stay on the
list with an explanation. Success messages disappear after three quiet seconds; errors remain.

## 37. Change requests, the second process and who publishes (FA6E)

For the organization's administrator. The decisions are in ADR-0199. **Section 40 (FA8) replaces the accepting, turning into a version and
asterisk-on-the-row parts of this section: a request is now one controlled change decided by one chain of steps, and the last step
publishes. The groups and Publisher parts still hold.**

**Two approval processes, one set of groups.** A template is approved by the process on **Template**. A change request is approved by
a second process, on **Change request** (Setup, Approval processes, choose the object **Change request**). Both name the same
groups (for example Plant QMS, then Corporate QMS), but **each process has its own steps**: adding, removing or changing a step in
one changes nothing in the other. If you want the same people in both, name the same groups in both. Nothing needs a code change.

**How to make it.** Setup, Approval processes, New: object **Change request**, name it (for example Change request review), add step 1
(approver: the group Plant QMS), add step 2 (approver: the group Corporate QMS) and tick **Skip this step when nobody can decide it**, make it
active. **Without such a process nobody can raise a change request**: the person is told that an administrator must make it. With it, the person who
raises a request cannot be a decider of their own request (the process does not allow self-approval), Plant QMS accept or decline first,
then Corporate QMS. A declined request is final; the person raises a new one. An accepted request is turned into a new version by the owner
of the template (or an approver), and that version goes through the **Template** process like any other.

**Who publishes.** Publishing, taking a template back and retiring belong to the access policy **Publisher**, which you give to a group in
Setup, Groups (or to a person). Approving does not give it. Give **Publisher** to the group that has the last word, **Corporate QMS**. In a
flow that has **no Corporate step**, give **Publisher** to **Plant QMS** for as long as that is so, and take it away again when the Corporate
step comes back; nobody checks this for you, so do it whenever you change who is in the flow. Without Publisher a person cannot publish,
take back or retire, whatever they approved. The **Organization administrator** holds every ability and can always publish. A change of
the policy applies at the next question, to every version, approved before or after.

**The tab and the asterisk.** Everyone with Author, Reader, Filler or Approver sees the tab **Change requests** and, in it, their own
requests; people who may change a template (its owner, Approver) see all the requests of it. A template with a request that is open or
accepted shows an asterisk at the right of its row and of its page.

## 38. The step that asks where a template is published (FA6G)

**What it is for.** In the template review, **Corporate QMS** knows where a template is meant to be used and **Plant QMS** publishes it.
You can make the Corporate QMS step ask for that when its people approve. It is only a **suggestion**: nobody sees the template because of
it, and nothing is shared. When Plant QMS publishes, the Publish window starts from the suggestion and Plant QMS may change it.

**Set it up (Setup, Approval processes, the process "Template review").** Open the step of Corporate QMS and tick **Ask the approver for a
choice**, then save the process (a new version of the process; requests already under way keep the old one). The process then has, in this
order: Plant QMS (checks and numbers), Corporate QMS (ticked; tick **Skip this step when nobody can decide it** as well if the group may be
empty), and a last step of Plant QMS. Give the access policy **Publisher** to the group **Plant QMS** only, as it holds the last word.

**What the approver sees.** At the ticked step, **Approve** opens a window with the locations of your lists. They must tick at least one
location. Under each ticked location they may tick departments, or none. Without a location the approval is refused, from the template
page and from the approvals inbox alike, and the task stays open. When the step is skipped (an empty group) there is no suggestion.

**What Plant QMS sees.** After the last approval, **Publish** opens with the suggested departments listed and a suggested location already
chosen. They may change all of it. Publish still needs a location **and at least one department** that has a group set up, and the
Publisher policy; department people see the template only after that Publish. A suggested place that your lists no longer offer is left
out, and the window says so.

**Nothing is written down but the fact.** The suggestion cannot be changed after the approval, by the approver or by anybody else. The audit
trail says that a suggestion was made and how many places it holds, never which.

## 39. View, Download, Sheet options, the footer row and Revisions (FA7)

**View.** On the **Sheet** tab of a template, once a revision is open, the button **View** shows the sheet the way Ctrl+P shows it in a
spreadsheet program: pages, the column labels and header rows repeated on every page, merged cells, column widths, row heights, bold,
italic and the sizes as the sheet has them. The cells a person fills in are shown empty. **Print** opens the browser's print dialog, which
also saves a PDF. The paper is A4, landscape when the sheet is wide, and the sheet is fitted to the page width. **Everybody who can read
the template sees View.**

**Download.** The button **Download** gives the sheet as an Excel file named after the template number and the revision, for example
`F-MR-16_Rev00.xlsx`. It is shown only when download is allowed for the template; the server checks the same setting when the file is
asked for, so a hidden button is not the rule. The people who hold the **Publisher** policy (and the Organization administrator) can always
download, so they can check their work. Everything in the file is plain text, never a formula, so a text that starts with `=` does not run
in a spreadsheet program.

**The two tick boxes.** When Plant QMS publishes, the Publish window has one row with **View only** and **Allow download**, both
unticked at first.
- **View only** means the template is a reference: nobody can start a fill-in from it (the control is not offered and the server refuses it
  in fixed words). The template can still be read, View still shows, and a fill-in that was already started can be finished and sent.
- **Allow download** shows Download to everybody who can read the template.

They belong to the **template**, not to one revision. People with the Publisher policy change them later without publishing again with
**Sheet options** on the template page (the same two boxes). The change is recorded by the template's identifier and the two answers.
Templates made before this release have both unticked: tick them in Sheet options if people should download them or if they should be
view only.

**The footer row.** Every template sheet ends with one more row, across the full width and bordered like the rest. At its right it reads
the template number and the revision, for example `F/MR/16  Rev No.'01'`. Until Plant QMS has numbered the template (the first approval)
the row is there and blank; when the template is numbered the number appears at once. The row is made when the sheet is shown, printed or
downloaded; it is **not** part of what the author writes, so the author cannot change it and the number is always the current one. The
revision in it is the sheet's active revision.

**Revision instead of Version.** Wherever you read the word for a sheet or a template it is now **Revision**, written with two digits:
the first version of a sheet is **Revision 00**, the next **01**. Nothing stored was changed or renumbered; only what you read.

## 40. A change request is one controlled change: the register, the revised copy and the next Revision (FA8)

For the organization's administrator. The decision is in ADR-0202. The app ships **no approval process**: you make the one for change
requests in Setup, and what follows works only with it.

**What a change request is now.** A person who can read a template presses **Ask for a change** and fills in the **Document Change
Register**: the request number (typed by hand, up to 30 characters, the way it is kept on paper today), the change requested, the basis for
change, the horizontal deployment and, if there is one, the format or document that stops being used and from which day. The platform writes
the rest of the top itself: the document name and number, the Revision in use, who asks and from which department. The person may also
point at nothing on the sheet any more (FA10): a request is the register alone. Only **one change is in progress on a template** at a time; the next can be asked for when the
first is published or declined.

**Form and Excel.** The register is shown as a **form** and as an **Excel page**, one click apart. They are the same values: the Excel page is
a drawing of the form and cannot be edited in its cells. While asking, the person can download the empty (or half-filled) register as an Excel
file, fill it there and **upload** it to fill the form. After the request is sent, values are changed in the form only.

**What you set up, once.** In Setup, Approval processes, make a process on the object **Change request**, for example *Change request
review*, with three steps:
1. **Plant QMS** (the group). An ordinary step.
2. **Corporate QMS** (the group). Tick **Ask the approver for a choice**: this step suggests where the template will be published. Tick
   **Skip this step when nobody can decide it** if the group may be empty.
3. **Plant QMS** again. This is the last step and it publishes. **Do not tick Skip on it**: a process whose last step can be skipped is
   refused when someone raises a request.
Make the process active. Create the groups (Setup, Groups; making a group needs the ability to manage access) and give the access policy
**Publisher** to the group that holds the last step, because the last approval is refused for a person who does not have it. The
Organization administrator holds every ability. A request cannot be raised while there is no such process, and the words say so.

**What the approvers do.**
- Each person who holds an open task of the request sees the request page, with two buttons in one row. **View Current Version** opens the Sheet tab of the template at the Revision in use, to read. **Create Revised Version** (for the people with an open task and for the Organization administrator) makes a copy of the Revision in use and takes you to the **Sheet tab, opened on that copy**. It is a draft held for the request: the people who only read the template never see it. Change it in the grid or the form, or **replace it from an Excel file** (the usual upload on the Sheet tab), then save; the Revision in use is never edited. Once the copy exists the button reads **Open Revised Version** and goes to the same place; for people who cannot make or change the copy it is not drawn. On the Sheet tab the people who change the copy also get **Download**, whatever *Allow download* says (that option is for readers of a published Revision).
- **View** (next to *Download as Excel* above the register) opens the register as it looks when printed: a white A4 page with the register's margins, merged boxes and borders, no grid lines and no headings. You can only read it; after the publish it shows the finished register.
- The people with an open task may complete the **bottom of the register**: department, approved by, signature, effective date, the format
  obsoleted and its day, and the two Yes/No answers. Open the **Form** view and press **Save the register**. What nobody fills in is
  filled in by the platform at the publish: the effective date is the day of the publish (UTC), both answers are **Yes**, *Approved by*
  lists the real approvers with the days, and the signature box reads *Approved electronically on* that day.
- At the **last step** the approval opens the **Publish window** (Location, Departments, and the options *View only* and *Allow download*),
  filled in from what Corporate QMS suggested. Confirming it is the last approval **and** the publish: the copy becomes the next Revision and
  the people of the chosen departments see it. If there is no revised copy yet, or the person does not hold the Publisher policy, the approval
  is refused in plain words and nothing changes.
- A step may **reject** the request (a reason is required). The request ends for good, the copy is closed, the Revision in use stays as it was
  and the person may ask again.

**Revisions.** The new Revision gets the next number, **without gaps**: a copy that was rejected never uses a number. The *New Rev. No.* and
*New Revision date* of the register are written by the platform when the request is published and nobody can type them. The template shows
**Revision number** (beside the Document number) on its Details tab and **Last revised on** in System information, in your own time zone; the footer row of the sheet, the file name and the list use the same Revision.

**What readers see.** A person who can only read a template sees the Revision in use and nothing else. Nobody starts a new draft of a published
template by hand any more: the old button on the template page is gone; a new Revision is made only from a change request. The first draft of a
brand-new template is not affected.

**The asterisk** is on the **Change requests tab** of a template while a change is in progress (open and not yet published or declined). It is no
longer on the rows of the list; the list has a **Revision** column.

**Limits to know.** Requests that were started before this release cannot be finished and should be declined. The Excel page of the register is
read-only. Fill-in and report screens still show the Revision as the stored number minus one, which can be off by the number of rejected
copies.

**Calmer screens (FA10, ADR-0204).** The *Ask for a change* popup holds the register only (nothing about places on the sheet; requests made
before keep the places they carry in the stored data). The grid of the Sheet tab no longer marks places or opens the popup when you press
Enter. **Submit for approval** on a template is greyed out when the template cannot be sent: it was published (a published template changes
only through a change request), it is retired, it is already being approved, or it has no first Revision in draft. The asterisk on the
**Change requests** tab sits beside the word and no longer makes the row of tabs scroll.
## 41. The application grid, Home in every application and the Quality Management System dashboard (FA9)

Decision: [ADR-0203](adr/0203-the-application-grid-home-in-every-application-placeholders-and-the-quality-dashboard.md). This is for the
platform administrator and the organization administrator.

**Which applications exist.** An organization sees the applications it made itself in Setup, the **Quality Management System** (plan feature
`inspection`) and, with the feature **Process applications** (`process-applications`), fifteen more that are only announced: Marketing,
New Product Development, Production, QA, Top Management, Purchasing, Stores, Packing - Dispatch & Product Delivery, HR - Training, IT,
Total Productive Maintenance, NPC, Tooling, Process Engineering and CVD. To give or take the feature: Plans, open the plan, Change, tick
the feature; or Organizations, open it, Features, switch it on or off for that organization. Taking it away hides the applications at once
and loses nothing (they hold no data). The applications you make yourself in Setup do not change: they have no Home tab of their own.

**The grid.** After signing in, `/` shows every application the person may open, as tiles, with a search box, one chip for each group, the
last three opened and a mark on the one they came from. A tile marked "Coming soon" is an application whose tabs are all still to be built.
The three lines in the strip open the same grid from any page. A person who may set things up also sees "Manage applications" and a "New
application" tile.

**Home.** Every shipped application opens on its Home. The Quality Management System's Home is the dashboard; the others show a plain
overview with one card for each tab.

**The dashboard.** Everyone who can open the application sees it, and every number is about what **that person** may see (the same lists as
the tabs). A person who may read only their own templates sees numbers about their own; a person who cannot read fill-ins at all sees no
fill-in tile. A number marked "at least" means more records exist than the page is allowed to read at once (50,000). Choose the period, a
location and a department at the top; the choice is in the address, so you can send the page to someone and the back button undoes a choice.
A tile that does not follow a chosen filter says so (for example "Not filtered by department"). Press a tile to open the list it counts, a
department bar to narrow the page, a template bar to open its fill-ins, a ring segment to open the templates in that status. "View as
table" shows the numbers of any graph as a table.

**Two things to know.** The department of a fill-in is the department of the person who started it, written when it is started; fill-ins
started before this release have none and are shown as "Not recorded". "Published per month" counts from this release on.

**Settings** (`platform.inspection.home.*`): `max-scanned` (50,000), `max-approvals` (500), `recent-activity` (10).

## 42. Identification, Part A and Part B, and the number of a template (FA11B)

Decision: [ADR-0206](adr/0206-identification-with-part-a-and-part-b-the-typed-start-of-a-series-and-the-numbering-information.md). This is for
the organization administrator and for Plant QMS.

**What a person types.** On a template (Edit) the person who sets the identity chooses an **Identification** (the kind of document). The
form then asks for **Part A** and **Part B** only when that kind needs them, and the **Document number** builds itself on the screen as they
are typed. Part A and Part B are typed freehand: capital letters, digits and a hyphen, up to 10 (the box corrects lower case as you type).
The last part of most numbers is a running serial:

- **A new combination** (this Identification with these Part A and Part B has no numbers yet): the serial box can be typed. Type the serial the
  series starts at (5 gives `...05`, padded as the pattern says), or leave it empty to start at 1. The next template of the same combination
  gets the number after it.
- **A combination that already has numbers:** the box is disabled and shows the next number. Nothing is typed.
- If two people start the same new combination at the same moment, the first one starts it and the second is told, in words, that the
  combination has just started; they save again and the number is automatic.
- Kinds with no serial (Control plan, Process flow chart and Failure mode analysis use both parts; Business plan uses Part B) show the whole
  number as soon as the parts are typed.

The number is fixed at the first approval, together with the Identification and the parts. Changing them afterwards is not possible
(a separate "renumber" act is not built).

**Which kinds exist and what they need.**

| Identification | Number | Part A | Part B | Serial |
|---|---|---|---|---|
| IMS | `IMS/01` | | | yes |
| QSP | `QSP-01` | | | yes |
| CORP-QSP | `CORP-QSP-01` | | | yes |
| ESSP | `ESSP-01` | | | yes |
| WI, SOP, F, CSF, PPT, L, R | `WI/<A>/01` | process or customer | | yes |
| F/EHS, L/EHS, R/EHS | `F/EHS/<A>/01` | process | | yes |
| EHS/AIR, HIR, AID, HID, AHID | `EHS/AIR/<A>/01` | process | | yes |
| PL/EHS | `PL/EHS/<A>/01` | process | | yes |
| EHS/EMP, EHS/OCP | `EHS/EMP/01` | | | yes |
| CP, PFC, FMEA | `CP/<A>/<B>` | process | part code | no |
| BP | `BP/<B>` | | year | no |

What Part A and Part B mean is only what the pattern does with them; the screen does not know.

**Add an Identification of your own.** Setup, Objects, Template, the **Identification** field: add the value (a short code). Then Setup,
Numbering: open the new value and save its pattern (`{field:partA}`, `{field:partB}`, one `{seq:N}`; at most 10 fixed characters). Until
you do, the new kind uses the default pattern (the identification, Part B when typed, a running count). The form follows a saved pattern at
once.

**See what has started.** Setup, Numbering lists, under the patterns, every combination that already has numbers: what was typed, the count
the next number gets and the count last given. A typing mistake (`PDR` for `PRD`) shows there as a combination of its own. A counter can be
raised, never lowered, from the pattern's page (as before).

**Who can type.** The people with the Approver policy (Plant QMS) edit the Identification, Part A, Part B and the serial; the Author does not.
You can give another group the same right with an access policy of your own (the fields `category`, `partA`, `partB` and `serial` of the
template). The shipped policies cannot be changed.

## 43. Where an application stands, and applications that share objects (FA13A)

Decision: [ADR-0225](adr/0225-the-order-of-applications-and-objects-and-policies-shared-between-solutions.md). This is for the people who
write a solution bundle (`solutions/<key>/solution.yml`). The list of every application is at the top of
[solutions/inspection.md](solutions/inspection.md).

**Where the application stands in the grid.** Give the bundle `order: 3` (a whole number from 1 to 999). The grid lists the bundles in the
order of that number, lowest first; two with the same number stand in the order of their keys; a bundle with no `order` stands after every bundle
that has one, in the order of its key. The order is data: it never depends on a name, and the browser draws the list as the server sends it. Leave
gaps (the first six places belong to Document Management, CAPA, Audit Management, Supplier Management, Management Core and, later, Environment
Management) so a new application can be put between two without moving the others.

**An application of its own that shows another bundle's records.** Do not copy the objects. The bundle that shows them says:

```
uses:
  - InspectionTemplate
```

and the file of each of those objects (`objects/<Object>.yml` of the bundle that brings it) says which other features give an organization the
object too:

```
requiresFeature: inspection
alsoAvailableWith: [document-management]
```

Both sides must say it: the loader refuses a `uses` the object's file does not back, and a feature an object lists that belongs to a bundle that
does not use it. The object then exists for an organization when its own feature **or any listed feature** is on; with none of them it is
"not found", exactly like an unknown object. The using bundle's entries may name the objects it uses (`object`, `readsObject`); it brings none
of its own for them. Its own feature decides whether **its tile** is shown; nothing is stored twice.

**An access policy shared the same way.** A policy of the bundle that brings it may say `alsoAvailableWith: [document-management]`. It is then
kept in an organization while the bundle's own feature or any listed feature is on, is made once, and is never removed. The loader refuses a
listed feature that is not another bundle's, and a policy that gives a right on an object the other bundle neither brings nor uses. Because the
policy is shared, a person who holds it sees every tile whose feature is on.

**A tile with only its Home.** A bundle whose application lists only `home` is a tile: it says "Coming soon" in the grid and shows its name and
"no tabs yet" on its Home. Add announced tabs with `placeholder: true`, as in the process applications.

**Plan features.** Each application that can be sold alone needs a feature row (a migration like V086, expand only, in no plan by default).
The bundle's `removalNote` is what the console shows before the feature is taken away.

## 44. Applications of one kind, and how to add another (FA13B)

Decision: [ADR-0226](adr/0226-applications-of-one-kind-on-shared-code-with-their-own-objects.md). The Quality Management System and Environment Management
run the same code over records of their own. **To add another application of this kind you write files and nothing else.**

**The recipe** (the example is `solutions/environment-management/`; copy it):

1. Make `platform-app/src/main/resources/solutions/<key>/` with `solution.yml`, `numbering.yml` and `objects/` holding three object files: the
   templates, the fill-ins and the review records of change requests. Give every name a new word: the objects (`<Word>Template`, `<Word>Fill`,
   `<Word>ChangeRequest`), the series key, the feature key, the application name, the policy names.
2. In `solution.yml`: `feature`, `label`, `order` (a free number), `uses: [DepartmentGroup]`, the three lines of `roles:` (`template`, `fill`,
   `changeRequest`), `application:` with the same entries (page keys `home`, `templates`, `change-requests`, `fill-ins`, `fill-in-report`), `objects`,
   `sheetOwners: [<the template object>]` and the access policies with names of their own.
3. Each object file says `requiresFeature: <your feature>`, `retainRecords: true`, `sharingDefault: PRIVATE` and keeps the same field names as the
   others (the functions read them), **including the platform-written text field `application` on the template object** (the shared functions write it; it stays empty). `numbering.yml` has one series whose `object` is the template object.
4. Add the feature to the department setup: `environment-management` is listed under `alsoAvailableWith` on `DepartmentGroup` and on the policy
   `departments` in `solutions/inspection/`; add yours there too if the new application publishes to departments.
5. A migration inserts the plan feature row (copy V087's first statement). Nothing else in the database changes.
6. Run the loader test, the baseline update and the document update of section 4, then `scripts/sync-migrations.ps1`.

What you do **not** write: Java, web code, a page, an endpoint, a hook. The proof is `ThirdApplicationIT`.

**Optional lines of `options:` (MC1, ADR-0257; MC1.1, ADR-0259).** `footerDate: "true"` (a second footer line with the day of the Revision in use; it belongs to the *documents* of the application, wherever they are shown), `publishTo: places` with `publishDepartment: <department code>` (reserved for an act that publishes to places; the publish of Document Management ignores them) and `routedByNumber: "true"` (below).

**An application whose documents come from Document Management (MC1.1, ADR-0259; the example is `solutions/management-core/`).** It needs one file and no object of its own: `solution.yml` lists the four objects of Document Management under `uses:` and names the same three `roles:`, says `options: routedByNumber: "true"` and has its `application:` with the same page keys. Each object file and each shared access policy lists the new feature under `alsoAvailableWith` (copy the lines of `management-core`). Its application is then the same records narrowed to those that carry its `apiName` in the field `application`; it shows no New document button. An organization chooses which documents belong to it in Setup, Numbering, Application routing (section 49).

**What is separate between two applications of the kind with objects of their own:** templates, fill-ins, change requests, number series and counters, approval processes (set up per object in
Setup), access policies, sharing, record locks, the lists, counts, search, report, dashboard, downloads, notices and the audit trail. **What is shared:**
the department setup and the organization's groups. (Management Core shares even the objects: it is Document Management's records narrowed by a field.)

**The feature off:** an application whose plan feature is off is "not found" everywhere, exactly like an unknown one; a forged application name changes
nothing.

## 45. A sheet in format version 2, and the switch (SH1A)

For the person who sets sheets up, and for whoever builds a bundle with sheets. The decision is ADR-0251; the contributor notes are in the engineering guide.

**What it is.** The platform can keep a sheet in a second, richer format beside the first. A sheet in format version 2 can say how a cell *looks*: its font (from a list of 16), its size (8 to 28 points), its text colour and background (any colour written `#rrggbb`), wrapping, where the text sits up and down, its four borders (thin, medium, thick, dotted, dashed, double) and its number format (one of ten). It can also name a title for its tab, freeze the first rows and columns, set how it prints (A4, A3 or Letter, portrait or landscape, fit to width, repeat the header rows), and say one by one that an input cell is a text, a number, a date, a list, a yes or no, or an **attachment** cell (up to five files), whether it is required, and who fills it (the filler or, from a later release, a reviewer). A cell with fixed text can carry a **link**: an `https` address only.

**What is different from the first format.** A fixed text may be 5,000 characters (a text typed into an input cell is still 1,000). A column may be as narrow as 8 pixels and a row as short as 4, so the narrow columns and short rows of the real forms fit. A sheet with no look at all is the first format's sheet with a key added.

**What you can and cannot do today.** The format is stored, checked, copied, reviewed, approved, published and retired exactly like the first. **It is not drawn yet** (the look appears on screen from SH4, in the file from SH3), the upload does not make it yet (SH1B), there are no formulas yet (SH2), and **a fill-in cannot be started on a template in this format** until SH5: the screen says "This template uses the new sheet format, which cannot be filled in yet." The present editor opens such a sheet and keeps everything it does not know; you can change text, widths, heights and merges and the look comes back untouched. The colour and size buttons of the editor still work (they use the first format's four colour names and four sizes); if you set one on a cell that already has a colour or size of the new kind, yours replaces it.

**The switch.** `platform.sheets.format-two.enabled` (the environment variable `PLATFORM_SHEET_FORMAT_TWO_ENABLED`) is **off** unless a profile switches it on. While it is off, saving a sheet in the new format and copying a version that is in it as a new draft both answer "This sheet is still in the earlier format.", and everything already stored is still read. The `local` profile and the automated tests have it on. Switch it on in a deployment only when the whole of the new sheet engine is built and tested.

**The limits** (`platform.sheets.limits.*`, new keys): `max-fixed-text-chars` 5,000, `min-column-width` 8, `max-column-width` 1,000, `min-row-height` 4, `max-row-height` 600, `max-sheets` 1, `max-files-per-cell` 5, `max-link-chars` 2,000. A setting can only narrow what the format allows. The screen reads them from the list of versions of a sheet.

**What a refusal looks like.** Every problem has a stable code (`look_conflict`, `link_invalid`, `link_target`, `input_picklist`, `freeze_range`, ...), a place made of the sheet's position, its key and identifiers, and fixed words. Nothing you typed is repeated in a refusal.

## 46. Formulas in a sheet (SH2)

**What it is.** In a sheet in format version 2 a cell can hold a **formula**, written the way a spreadsheet program writes it, and the platform works out the answer. The answer is calculated by the server when it is asked for; it is never stored with the sheet. A formula does not move when you add, move or remove a row or a column: it points at rows and columns by their permanent names, and only the text you read changes.

**How to write one.** In the author's grid select a cell and type `=` and the formula (in the cell or in the formula bar above the grid), then Enter. To type text that starts with an equals sign, put an apostrophe in front of it. For a sheet where the filler adds the rows, a column can hold **one formula for the whole column** (the form view, "Formula of column"): it is drawn at every row the filler adds, written for the first row as if that row were the only one (`=IF(A2="","",A2*2)`). A formula cell is a fixed cell: nobody types into it.

**Where the answer shows.** In the grid, as plain text (an error as its code, such as `#DIV/0!`). While you type the browser shows its own answer; once the sheet is saved the grid shows the server's, which is the one that counts. The look of the answer (colours, number formats) comes later. The download writes the answer as text, not as a formula, and says so in the file's information.

**The forty functions**, with one example each:

| Kind | Function | Example |
|---|---|---|
| Arithmetic | `ABS` `MOD` `INT` `ROUND` `ROUNDUP` `ROUNDDOWN` `SQRT` `POWER` `PRODUCT` | `=ROUND(A2/B2*100,1)` |
| Logic | `IF` `IFERROR` `AND` `OR` `NOT` `IFS` `SWITCH` | `=IF(B2="","",IF(B2>=85,"Pass","Fail"))` |
| Tests | `ISNUMBER` `ISTEXT` `ISBLANK` | `=IF(ISBLANK(A2),"",A2)` |
| Conditional totals | `SUMIF` `COUNTIF` `AVERAGEIF` | `=COUNTIF(C2:C20,"Fail")` |
| Statistics | `SUM` `AVERAGE` `MEDIAN` `MODE` `COUNT` `COUNTA` `MIN` `MAX` `LARGE` `SMALL` | `=MIN(100,SUM(B2:B9))` |
| Text | `VALUE` `TEXT` `CONCATENATE` `LEFT` `RIGHT` `LEN` `UPPER` `TRIM` | `=CONCATENATE(A2," - ",TEXT(B2,"0.0%"))` |

`COUNT` takes only cells and ranges. `TEXT` takes one of nine formats as a text in quotes: `0`, `0.0`, `0.00`, `0.000`, `0%`, `0.0%`, `0.00%`, `#,##0`, `#,##0.00`. A dot is always the decimal point, whatever the language of the browser. Rounding follows the spreadsheet program (`ROUND(2.675,2)` is 2.68). A blank cell counts as nothing in a total and as 0 in arithmetic.

**What is refused, and why.** Anything that could read the clock, the network, another file or run text (`INDIRECT`, `TODAY`, `NOW`, `RAND`, `OFFSET`, `HYPERLINK`, `WEBSERVICE` and the like); another sheet or another workbook; names; joining with `&` (use `CONCATENATE`); array formulas; `STDEV`, `VAR`, `RANK` and `N` (left out until their edge cases agree in both engines). A formula that depends on itself, directly or through other formulas, is refused (`A formula depends on itself`), and so is a chain of more than 10 formulas that need each other. Every refusal has fixed words that never repeat what you typed.

**The limits** (`platform.sheets.formulas.*`): 4,000 characters, 32 levels of brackets, 1,000 parts, 100,000 steps, 100,000 characters of text, 2 seconds per sheet, 10 formulas in a chain, 500 formulas per sheet. A bound reached while calculating gives the answer `#LIMIT!`.

**From a workbook.** With format version 2 on, an uploaded formula comes in as a formula when it uses only these functions and points only at cells the sheet can name; the report counts the ones that did not ("3 formulas used a function the sheet does not support were kept as their last value"). A group of cells sharing one formula each gets its own. In a sheet where the filler adds the rows, a formula in the first row that points only at its own row (or at the whole of a column) becomes that column's formula. The file is never refused because of a formula.

**The whole body of a column** (`=AVERAGE(B2:B6)` over every row) grows by itself with the rows the filler adds, and only in a sheet where the filler adds all the rows. In a sheet that names its rows, name the rows you mean.

## 47. What the download carries (SH3)

A template downloads as an Excel file written from its stored version. **A sheet in format version 1** is as before: text, widths, heights, merges, bold, italic, underline, four sizes, four colours (now the exact values `#93418b`, `#1e7b34`, `#b3261e`), every cell bordered, the band of column labels, the footer row. **A sheet in format version 2** also carries: the font (one fixed family for each of the 16 keys; a computer without it shows a nearby one), size, colours and fills, the four borders, the ten number formats, alignment and wrap, the frozen pane, the conditional colours, the page set-up (paper, orientation, fit to width, repeated header rows), `https` links, and **formulas as formulas** with the calculated answer stored beside each, so a program shows the right number before it recalculates; a calculated column has its formula at every sample row. Yes/No and picklist cells get a drop-down list.

**What it does not carry:** a print area, images, charts, real check boxes (a Yes/No cell is a two-value list), comments, protection, macros, a password, a list with a comma in a value or over 255 characters, the date in the footer stamp. A text that starts with `=`, `+`, `-` or `@` is text. A column comes back from an upload within 5 pixels of its width. Cells with no look and no border are not bordered, except the cells a filler types into.

## 48. What the screen draws of a sheet (SH4)

For an author of a template (see section 27 for the sheet, 45 to 47 for the format, the formulas and the file):

- **A sheet in the new format looks on the screen like the spreadsheet it came from**: the fonts (one of sixteen kinds), the sizes (8 to 28 points), the text colours and fills as they are stored, wrapped text, top, middle or bottom alignment, the borders of the four sides (thin, medium, thick, dotted, dashed, double) with one line between two neighbours, the ten number formats, merged areas, the stored widths and heights, the frozen rows and columns, and the conditional colours. It is drawn on white paper even when the rest of the screen is dark, so your colours are never changed.
- **A formula cell shows its answer**, written in its number format; an error shows its code. The bar above the grid still shows and edits the formula text.
- **A cell that is coloured by a rule** also says so in words to a screen reader ("highlighted: above 5"); a Yes/No cell shows the words Yes and No; an attachment cell shows "(no attachments yet)".
- **A cell a person types into** has a small mark in its corner and thin lines, whatever its colour; in the author's grid a Yes/No or list cell shows a small arrow.
- **Frozen rows and columns** stay in view while you scroll. If a merged area would be cut by the line, the line moves up (or left) to the nearest place that cuts nothing, and the fixed part never takes more than three fifths of the window.
- **What it does not draw yet:** setting a font, a fill or a border by hand (the author editor, SH6); the controls a filler uses for Yes/No, lists and files (SH5); several worksheets and their tab bar (SH7). Text does not run into empty neighbouring cells; it is clipped at the cell. A number whose alignment was stated as left is shown at the right.
- **Print** draws the same look with only the borders you stated (plus thin lines round the boxes a filler types into), the repeat-header and fit-to-width choices of the sheet, and the paper and orientation as before. The browser's own page breaks can differ from the spreadsheet program's.

## 49. Management Core, and how documents are routed to it (MC1, MC1.1)

Decisions: [ADR-0259](adr/0259-documents-are-made-in-document-management-and-routed-to-applications-by-their-number.md) (routing, the narrowed application, the notice on publish) and [ADR-0257](adr/0257-management-core-shared-counters-places-only-publishing-the-footer-day-and-the-owners-of-the-new-format.md) (the footer day). The page of the application is [solutions/management-core.md](solutions/management-core.md).

**What an administrator does, in order**

1. **Give the feature.** In the console give the plan feature Management Core to a plan or to one organization. Document Management is where documents are made, so the organization needs it too.
2. **Assign the access policies** (Setup, Access policies, or to a group): they are Document Management's: Author (writes documents), Approver, Publisher (the group with the last word), Reader, Filler, Reviewer and Department setup, and the **Observer** (sees every document, fill-in and change request and changes nothing: top management). Roles alone give no sight of a document (documents are private); groups and access policies do.
3. **Route documents to the application** (Setup, Numbering, the tab **Application routing**; shown only when the organization has an application a number can route to). Make a rule: an Identification (from your list), optionally a Part A, and the application. For example `F` with Part A `MC` belongs to Management Core. The rule for the exact pair wins over the rule for the identification alone. A rule decides the documents **numbered afterwards**: when Plant QMS gives a document its number, the platform writes the application on it; until the first approval fixes the number a new Save follows the rules as they are, at the first approval the rules are looked up once more (a rule that fits then is written; one that no longer fits clears nothing), and from then on nothing moves the document. Change or remove a rule whenever you like.
4. **Set up the approval processes** (Setup, Approvals): the process of the document (object Template) is Document Management's and serves every application. For fill-ins you can make one process for the documents of Document Management alone (entry criteria `ISBLANK(application)`) and one for each application (for example `application = 'management-core'`): the engine takes the one whose criteria the fill-in meets and refuses when two fit.
5. **Set up the numbers** (Setup, Numbering, Formats): the patterns of the Document series; routing changes nothing about numbers.
6. **Set up the departments and plants** (below) so that Plant QMS can publish to the creator's group.

**What a person sees.** The document appears in Management Core as soon as it has its number, and stays in Document Management, which shows every document; the field **Application** on the document (Details) says which. Management Core has no New document button. A document with no matching rule belongs to Document Management alone. The fill-in and the change request copy the application of their document. When a document is published, the person who made it gets a notice and an e-mail (the category **Documents** in Notification preferences switches each channel).

**Departments and plants: where they live and how to manage them.** A plant is a value of the list **Location** of the object **Department group**; a department is a value of the list **Department**. Plant QMS publishes a document to a location and a department: the group of the people there. So for every plant that receives documents, make a **Group** of the people (Setup, Groups) and add one **Department group** record (the Department group entry in the strip, or Setup): Location = the plant, Department = the department, Group = that group. A pair without that record is shown in the Publish dialog as "no group set up yet" and the publish says no for it. A group with nobody in it also says no. Removing a plant means setting its Location value inactive; a document already published keeps its shares. The demo script makes the departments **Level 1** and **Level 2** and a record for each plant and level that points at the group of that level.

**The footer.** A document that belongs to an application that asks for it (Management Core does) shows, below the number and Revision, `Date: dd-Mon-yyyy`, the day the Revision in use was published, wherever the document is shown. On the screen and in print it is the reader's day; in the downloaded file it is the day in the zone `platform.inspection.footer.zone` (default UTC), so near midnight the two can differ by a day. A Revision from the history has no stored day and shows the first line only.

**Uploading the workbook.** On the Sheet tab of a document choose Upload, pick the worksheet and save. With `platform.sheets.format-two.enabled` on, an upload makes a sheet in the new format; the list `platform.sheets.format-two.owners` is empty, so every document does.

## 50. Filling a form on the new sheet format (SH5)

**Kinds of cells.** A cell of a form built on the new format states its own kind in the sheet (`inputs`): text, number, date, a list of
values, yes or no, or an attachment. A cell that states nothing is as its column says. Each cell may also state whether it is
required and whether it is the filler's or the reviewer's.

**The person who fills.** Starting a fill-in on a published form, filling it in, adding files and sending it is as for any fill-in. The
required cells of the filler are asked for when the form is sent, never while it is saved. A calculated column fills itself in at every
row the filler adds and is never typed. A cell meant for the reviewer is read only for the filler.

**The reviewer.** While the review is pending, a person who has an open task of it can fill the cells meant for the reviewer; the
rest of the form is read only for them. Every save of the reviewer is recorded in the audit trail by its size and a fingerprint, never
by what was written. It does not send the form back and does not restart the review. (Reviewers chosen per plant and the score are
the next part of Management Core.)

**Files.** A file is added to the form as a whole and named in the attachment cell. A cell holds up to the number of files the sheet
states (5 at most). Taking a file off removes it from the cell at the same time; a form that was sent cannot lose the last file of a
required attachment cell; when the form is sent, a required attachment cell must still have a file that exists.

**Reports.** A calculated column marked reportable is copied to the report with its answer when the form is saved; a calculation that
ends in an error is not copied. An attachment cell is never in a report.

## 51. Publishing a document to the plants, choosing the reviewers and the monthly fill-in (MC2A)

This is the administrator's view of what the creator of a Management Core document does after Document Management has published it, and of what an
administrator sets up for it. The decision is ADR-0260.

**What an administrator sets up once.** (1) For each plant, a group of the plant heads of that plant, with its members; (2) Setup, Department groups: a
record for each plant with the department **PLANTHEAD** that points at that group (a plant with no record, or whose group has no members, cannot be
published to); (3) the Filler and Reader policies for the plant heads' groups; (4) Setup, Approvals, on the fill-in object, a process with the entry
criteria `application = 'management-core'` whose first step uses the rule **Publication reviewers** (it takes no value) and whose last step names the
top management group; (5) the licence of every person who may be chosen as a reviewer (a person without one is left out of a review).

**What the creator does.** Open the document in Management Core (it must have a Revision in use), choose **Publish to plants**, tick the plants and
choose the reviewers of each plant (people or groups, at least one). **Change plants** changes the plants and the reviewers; unticking a plant takes
the document back from it. The act is the creator's own; a member who holds the ability **Publish documents to plants** may do it for a document they
did not make (give it with an access policy; the Organization administrator holds every ability).

**What happens.** The plant heads of each added plant can read the document and are told (a notice and an e-mail, switchable under Documents). They
start a fill-in each month: the plant (asked only of a person who is plant head of several) and the month (this month, or an earlier one to catch up).
One fill-in exists for a document, a plant and a month; a rejected one is returned to its filler. The other plant heads of the plant can read it and
cannot change it. When the fill-in is sent, step 1 is asked of the reviewers chosen for its plant, groups expanded at that moment; a change of
reviewers afterwards never reaches a review that is running.

**What it does not do.** Unpublishing or retiring the document in Document Management leaves the plants; taking them back is the creator's act. A
reviewer should hold no policy that views every fill-in (Reviewer, Approver, Observer): such a policy shows every fill-in whatever the plant. A plant
with no reviewer cannot be saved, and a draft at a plant that was taken back cannot be sent until the plant is given the document again.

## 52. The score, the limit in Application settings and the Action plan (MC2B)

**What a bundle writer needs to know.** A `solution.yml` may declare `settings:` for its applications: each has a `key` (lower camel case), a `label`,
a `description`, a `kind` (only `PERCENT` today: a whole number from 1 to 100) and the `default` the application starts with. The default must be a
value the kind accepts and a key may not be used twice, or the application does not start. An organization changes it in Setup, Application settings
(ability `application-settings.manage`); the module that owns the application's functions asks it through the shared-kernel contract
`ApplicationSettings.value(application, key)`. Management Core declares `scoreLimit` (default 85).

**What an author needs to know.** A sheet in format version 2 carries one property, `score`: the key of a cell that holds a formula. In the grid of a
draft, select a formula cell and tick **Use as the score** beside the formula bar. The mark is kept only while the cell holds a formula; a new
Revision made by uploading a file again must be marked again, or fill-ins on it have no score. The platform reads the percent from that cell at every
approval: a cell shown as a percent (0.9) counts as 90 points; a number outside 0 to 100 or an error is "cannot be calculated" and stops the approval.

**What an administrator needs to know.** The result is `ACTION_PLAN` only strictly below the limit. The fill-in object has three platform-written fields,
`score`, `result` and `parentFill`; give a policy that reads fill-ins the three in its `read` list, or they do not exist for it. The two processes of
Management Core fill-ins must not both fit one fill-in: the Plant activity one carries `ISBLANK(parentFill)` and the Action plan one
`NOT(ISBLANK(parentFill))`. The Action plan's reviewers are those of the fill-in it answers.

## 53. The Home dashboard that compares the plants (MC3)

**What a bundle writer needs to know.** An application of the kind that routes its documents by number and asks for a monthly fill-in may say, in its
`options`, `dashboard: management`. Its Home page is then the dashboard that compares the plants (the other options it needs are `routedByNumber` and
`monthlyFillIn`; the start stops without them). The application declares three settings for it: `scoreLimit` (the Action plan rule), and the words of the
bands, `excellentAbove` and `goodAbove`. A setting may name another it must stay **below** (`below: excellentAbove`): the default must already obey it, and
Setup refuses a value that does not. The API lists the application's options with the application; a screen may read them and invents none.

**What an administrator needs to know.** Setup, Application settings: **Excellent above** (default 85) and **Good above** (default 65) only word a score on
the dashboard (Excellent above the first, Good above the second, Poor otherwise; exactly the line belongs to the lower band). They do not change who needs
an Action plan: that is the **score limit** alone. Give the group that should see every plant a policy that views every fill-in (the Observer, or the
Approver); a plant head sees only their plant, and a person with no policy that reads fill-ins is shown no numbers. A score counts on the dashboard when its
fill-in is **approved**; a fill-in that waits is counted under "Waiting for review" or "Waiting for top management" until then.

**What a contributor needs to know.** `ManagementHome` makes the page from header facts the record store returns (never a rule of its own for who sees a
record); `ManagementNumbers` is the pure arithmetic and the place to change a counting rule; a new tile or chart is a field of `ManagementHomeViews` (with the
`Management` prefix: the OpenAPI generator merges two classes of one simple name), a case in `ManagementNumbers` with a test, and a component in
`platform-web/src/solutions/inspection/management/`. A tile that opens a list needs a slice the list understands (`addressSlice.ts`).
