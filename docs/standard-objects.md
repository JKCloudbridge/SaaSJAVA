# Standard objects and fields

This file is generated from the definition files in `platform-app/src/main/resources/metadata/standard`. Do not edit it by hand: change the files and refresh it as described in [metadata-guide.md](metadata-guide.md). A test fails when the two differ.

Every object also has the system fields listed first. Organizations add their own fields to an object where *Own fields* says yes; their names always end in `__c`.

## System fields (every object)

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `id` | Record ID | TEXT |  | yes | up to 36 characters |
| `sequence` | Sequence | AUTO_NUMBER |  | yes | `` + number, 1 digits, from 1 |
| `description` | Description | LONG_TEXT |  |  | up to 32000 characters |
| `ownerId` | Owner | LOOKUP | yes |  | points to `User` |
| `createdAt` | Created at | DATETIME |  |  |  |
| `createdById` | Created by | LOOKUP |  |  | points to `User` |
| `updatedAt` | Last changed at | DATETIME |  |  |  |
| `updatedById` | Last changed by | LOOKUP |  |  | points to `User` |

## AccessPolicy

A named set of abilities and permissions on data that can be given to members and to groups, on top of their profile.

- Label: Access policy / Access policies
- Records managed by: security
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `name` | Access policy name | TEXT | yes | yes | up to 80 characters |
| `isCustom` | Custom | BOOLEAN |  |  | default `true` |
| `licenceTypeId` | Required licence type | LOOKUP |  |  | points to `LicenceType` |

## AccessPolicyAssignment

One access policy held by one user.

- Label: Access policy assignment / Access policy assignments
- Records managed by: security
- Own fields: no

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `assigneeId` | User | MASTER_DETAIL | yes |  | points to `User` |
| `accessPolicyId` | Access policy | MASTER_DETAIL | yes |  | points to `AccessPolicy` |

## Account

A company or organization that the business sells to, buys from or works with.

- Label: Account / Accounts
- Records managed by: the organization (ordinary data)
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `name` | Account name | TEXT | yes |  | up to 255 characters |
| `type` | Account type | PICKLIST |  |  | values: Customer, Prospect, Partner, Vendor, Other |
| `industry` | Industry | PICKLIST |  |  | values: Agriculture, Banking, Construction, Education, Energy, Finance, Government, Healthcare, Hospitality, Manufacturing, Media, Retail, Technology, Telecommunications, Transportation, Other |
| `parentAccountId` | Parent account | LOOKUP |  |  | points to `Account` |
| `website` | Website | URL |  |  | up to 2048 characters |
| `phone` | Phone | PHONE |  |  | up to 40 characters |
| `email` | E-mail | EMAIL |  |  | up to 254 characters |
| `annualRevenue` | Annual revenue | CURRENCY |  |  | 18 digits, 2 after the point |
| `employeeCount` | Employees | NUMBER |  |  |  |
| `isActive` | Active | BOOLEAN |  |  | default `true` |
| `billingStreet` | Billing street | TEXT |  |  | up to 255 characters |
| `billingCity` | Billing city | TEXT |  |  | up to 80 characters |
| `billingState` | Billing state or province | TEXT |  |  | up to 80 characters |
| `billingPostalCode` | Billing postal code | TEXT |  |  | up to 20 characters |
| `billingCountry` | Billing country | TEXT |  |  | up to 80 characters |
| `shippingStreet` | Shipping street | TEXT |  |  | up to 255 characters |
| `shippingCity` | Shipping city | TEXT |  |  | up to 80 characters |
| `shippingState` | Shipping state or province | TEXT |  |  | up to 80 characters |
| `shippingPostalCode` | Shipping postal code | TEXT |  |  | up to 20 characters |
| `shippingCountry` | Shipping country | TEXT |  |  | up to 80 characters |

## Case

A question, request or problem raised by an account or a contact, followed until it is closed.

- Label: Case / Cases
- Records managed by: the organization (ordinary data)
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `caseNumber` | Case number | AUTO_NUMBER |  | yes | `CASE-` + number, 6 digits, from 1 |
| `subject` | Subject | TEXT | yes |  | up to 255 characters |
| `status` | Status | PICKLIST | yes |  | values: New, InProgress, Escalated, Resolved, Closed; default `New` |
| `priority` | Priority | PICKLIST |  |  | values: Low, Medium, High, Critical; default `Medium` |
| `origin` | Origin | PICKLIST |  |  | values: Phone, Email, Web, Chat |
| `accountId` | Account | LOOKUP |  |  | points to `Account` |
| `contactId` | Contact | LOOKUP |  |  | points to `Contact` |
| `closedAt` | Closed at | DATETIME |  |  |  |
| `resolution` | Resolution | LONG_TEXT |  |  | up to 32000 characters |

## Contact

A person the business deals with, usually at an account.

- Label: Contact / Contacts
- Records managed by: the organization (ordinary data)
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `firstName` | First name | TEXT |  |  | up to 80 characters |
| `lastName` | Last name | TEXT | yes |  | up to 80 characters |
| `accountId` | Account | LOOKUP |  |  | points to `Account` |
| `reportsToId` | Reports to | LOOKUP |  |  | points to `Contact` |
| `title` | Job title | TEXT |  |  | up to 128 characters |
| `department` | Department | TEXT |  |  | up to 80 characters |
| `email` | E-mail | EMAIL |  |  | up to 254 characters |
| `phone` | Phone | PHONE |  |  | up to 40 characters |
| `mobilePhone` | Mobile phone | PHONE |  |  | up to 40 characters |
| `birthDate` | Birth date | DATE |  |  |  |
| `isActive` | Active | BOOLEAN |  |  | default `true` |
| `mailingStreet` | Mailing street | TEXT |  |  | up to 255 characters |
| `mailingCity` | Mailing city | TEXT |  |  | up to 80 characters |
| `mailingState` | Mailing state or province | TEXT |  |  | up to 80 characters |
| `mailingPostalCode` | Mailing postal code | TEXT |  |  | up to 20 characters |
| `mailingCountry` | Mailing country | TEXT |  |  | up to 80 characters |

## LicenceType

A kind of licence the platform sells, for example the licence of an administrator or of a user.

- Label: Licence type / Licence types
- Records managed by: licensing
- Own fields: no

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `key` | Key | TEXT | yes | yes | up to 40 characters |
| `name` | Licence type name | TEXT | yes |  | up to 80 characters |
| `kind` | Kind | PICKLIST | yes |  | values: Seat, AddOn |

## Opportunity

A possible sale to an account, followed from first interest until it is won or lost.

- Label: Opportunity / Opportunities
- Records managed by: the organization (ordinary data)
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `name` | Opportunity name | TEXT | yes |  | up to 255 characters |
| `accountId` | Account | LOOKUP |  |  | points to `Account` |
| `primaryContactId` | Primary contact | LOOKUP |  |  | points to `Contact` |
| `stage` | Stage | PICKLIST | yes |  | values: Prospecting, Qualification, Proposal, Negotiation, ClosedWon, ClosedLost; default `Prospecting` |
| `amount` | Amount | CURRENCY |  |  | 18 digits, 2 after the point |
| `probability` | Probability | PERCENT |  |  | 5 digits, 2 after the point |
| `closeDate` | Close date | DATE | yes |  |  |
| `leadSource` | Lead source | PICKLIST |  |  | values: Web, Referral, Partner, Event, Other |
| `nextStep` | Next step | TEXT |  |  | up to 255 characters |

## Profile

The base of a member's access, meaning the licence they hold and the abilities and permissions that come with it.

- Label: Profile / Profiles
- Records managed by: security
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `name` | Profile name | TEXT | yes | yes | up to 80 characters |
| `licenceTypeId` | Licence type | LOOKUP | yes |  | points to `LicenceType` |
| `isSystem` | System profile | BOOLEAN |  |  |  |
| `isDefault` | Default profile | BOOLEAN |  |  |  |

## Role

A place in the role hierarchy. It decides which records a member can see, never what they may do.

- Label: Role / Roles
- Records managed by: security
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `name` | Role name | TEXT | yes | yes | up to 80 characters |
| `parentRoleId` | Parent role | LOOKUP |  |  | points to `Role` |

## User

A person who is a member of the organization and can sign in to it.

- Label: User / Users
- Records managed by: identity
- Own fields: yes

| API name | Label | Type | Required | Unique | Details |
|----------|-------|------|----------|--------|---------|
| `firstName` | First name | TEXT |  |  | up to 80 characters |
| `lastName` | Last name | TEXT | yes |  | up to 80 characters |
| `username` | Username | TEXT | yes | yes | up to 80 characters |
| `email` | E-mail | EMAIL | yes | yes | up to 254 characters |
| `profileId` | Profile | LOOKUP | yes |  | points to `Profile` |
| `roleId` | Role | LOOKUP |  |  | points to `Role` |
| `managerId` | Manager | LOOKUP |  |  | points to `User` |
| `title` | Job title | TEXT |  |  | up to 128 characters |
| `phone` | Phone | PHONE |  |  | up to 40 characters |
| `isActive` | Active | BOOLEAN |  |  | default `true` |
