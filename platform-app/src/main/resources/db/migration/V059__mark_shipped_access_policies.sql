-- Access policies that ship with the platform (Functional track FA1, ADR-0175, ADR-0181).
--
-- A platform-shipped solution (a cluster of business function) comes with access policies: rows in every organization
-- that has the solution's plan feature, made the way the system profiles are, but marked so that the organization can
-- assign them to people and groups and can neither change nor remove them. The marker is three columns:
--
--   managed_by    who owns the row ('solution'), null for a policy an administrator made;
--   managed_key   the stable identity of the shipped policy inside its owner ('demo.reader'), so that making the
--                 policies again finds the ones that exist whatever an administrator did to names around them;
--   managed_hash  a fingerprint of what the platform declares for the policy, so that a new release which changes the
--                 rights of a shipped policy is noticed with one comparison and the row is brought up to date.
--
-- The database refuses to insert, change or remove a marked row unless the transaction says it is the platform keeping
-- the policy up to date (set_config('platform.shipped_policy_write', 'on', true), the pattern of
-- platform.record_recalculation, V043). A forged marker from an administrator's request, or a bug in a later code path,
-- therefore cannot make a policy "shipped" nor alter one that is. Assigning a policy to a member or a group changes
-- other tables and is not affected. Expand only: nullable columns, nothing is renamed.

alter table access_policy
    add column managed_by   text,
    add column managed_key  text,
    add column managed_hash text;

alter table access_policy
    add constraint access_policy_managed_shape check (
        (managed_by is null and managed_key is null and managed_hash is null)
        or (managed_by = 'solution' and managed_key ~ '^[a-z][a-z0-9.-]{0,79}$' and managed_hash is not null));

create unique index access_policy_tenant_managed_key on access_policy (tenant_id, managed_by, managed_key)
    where managed_by is not null and deleted_at is null;

create function platform_shipped_policy_guard() returns trigger
    language plpgsql
as $guard$
begin
    if coalesce(current_setting('platform.shipped_policy_write', true), 'off') = 'on' then
        return new;
    end if;
    if tg_op = 'INSERT' then
        if new.managed_by is not null then
            raise exception 'shipped policy guard: only the platform makes a shipped access policy'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if old.managed_by is null then
        if new.managed_by is not null then
            raise exception 'shipped policy guard: an access policy cannot become a shipped one'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.managed_by is distinct from old.managed_by or new.managed_key is distinct from old.managed_key
            or new.managed_hash is distinct from old.managed_hash or new.name is distinct from old.name
            or new.description is distinct from old.description or new.abilities is distinct from old.abilities
            or new.required_licence_type_id is distinct from old.required_licence_type_id
            or new.deleted_at is distinct from old.deleted_at then
        raise exception 'shipped policy guard: a shipped access policy is changed and removed by the platform only'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_shipped_policy_guard() is
    'A shipped access policy (managed_by set) is made, changed and removed only by a transaction that sets platform.shipped_policy_write (ADR-0181).';

create trigger access_policy_shipped_guard before insert or update on access_policy
    for each row execute function platform_shipped_policy_guard();

comment on column access_policy.managed_by is
    'Who owns a shipped access policy (solution); null for a policy an administrator made (ADR-0181).';
