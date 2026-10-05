-- The key of an object or field now is its API name (Sprint 10, ADR-0058).
--
-- Until now an object was named by a lower case key such as 'object-a'. From this sprint on objects and fields have API
-- names that mix cases ('Account', 'Employee__c', 'accountId', 'salary__c'), and the permissions on data and the audit
-- trail name an object or field by exactly that name. This migration widens the shape checks of the three places that
-- store such a name so that upper case letters are accepted. It is an EXPAND step in the sense of ADR-0009: every value
-- that was valid before is still valid, nothing is rewritten, and a release that still writes lower case keys keeps
-- working.
--
-- Hyphens stay allowed in these three checks, because rows written before this sprint may contain them. The checks of
-- the new definition tables (V031) are stricter and do not allow them.
--
-- audit_record is append-only; adding a check constraint does not touch a row, it only validates the existing ones.

alter table object_permission drop constraint object_permission_key_shape;
alter table object_permission add constraint object_permission_key_shape
    check (object_key ~ '^[A-Za-z][A-Za-z0-9_-]{0,59}$');

alter table field_permission drop constraint field_permission_key_shape;
alter table field_permission add constraint field_permission_key_shape
    check (field_key ~ '^[A-Za-z][A-Za-z0-9_-]{0,59}[.][A-Za-z][A-Za-z0-9_-]{0,59}$');

alter table audit_record drop constraint audit_record_object_key_format;
alter table audit_record add constraint audit_record_object_key_format
    check (object_key is null or object_key ~ '^[A-Za-z][A-Za-z0-9_.-]{0,119}$');
