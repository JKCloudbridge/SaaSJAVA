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
- A custom object or field can be removed; its permissions end with it; an object other fields point to cannot be removed until those fields are.
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
| Records (the `id` in the record address, the `sequence` counting) | 14 | `ObjectUsage` is the seam that keeps an object with records from being removed |
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
- **Preview.** Each layout and record page has a preview that draws the structure with made-up values. It is a picture of the structure; no records exist yet.
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
