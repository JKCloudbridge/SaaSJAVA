-- Carried note (Sprint 3): the check of a sign-in grant made on every API request: the grant joined with the user's
-- status and security version (the statement of AuthorizationStore.introspect), then the binding to the organization.
-- Two thirds of the grants in the table are alive; the request names an alive one.
\set g random(1, :users)
select a.id, a.user_id, a.bound_tenant_id, a.access_token_scopes, a.access_token_expires_at, a.created_at
  from oauth2_authorization a join platform_user u on u.id = a.user_id
 where a.access_token_value = encode(sha256(('tok' || :g)::bytea), 'hex') and a.access_token_expires_at > now()
   and a.deleted_at is null and a.revoked_at is null and u.deleted_at is null and u.status = 'ACTIVE'
   and u.security_version = a.security_version and a.created_at + make_interval(secs => 2592000.0) > now();
-- The application reads the binding by the grant's identifier (AuthorizationStore.introspect), a primary key lookup.
select bound_tenant_id from oauth2_authorization where id = (select id from oauth2_authorization
    where access_token_value = encode(sha256(('tok' || :g)::bytea), 'hex') and deleted_at is null);
