-- The tenant-first indexes of the proof (what the metadata-driven index policy of Sprint 16 will create, one per
-- field flagged "indexed", plus the two structural ones). All partial on live rows, all led by tenant and object, and
-- all on plain typed columns: the "slots" that hold a copy of an indexed field (ADR-0082, amendment 1).

create index object_record_list on object_record (tenant_id, object_api_name, id) where deleted_at is null;
create index object_record_bin on object_record (tenant_id, object_api_name, deleted_at, id) where deleted_at is not null;

create index object_record_s_text_1 on object_record (tenant_id, object_api_name, s_text_1, id) where deleted_at is null;
create index object_record_s_text_2 on object_record (tenant_id, object_api_name, s_text_2, id) where deleted_at is null;
create index object_record_s_num_1 on object_record (tenant_id, object_api_name, s_num_1, id) where deleted_at is null;
create index object_record_s_date_1 on object_record (tenant_id, object_api_name, s_date_1, id) where deleted_at is null;
create index object_record_s_ref_1 on object_record (tenant_id, object_api_name, s_ref_1, id) where deleted_at is null;

create unique index object_record_link_pair on object_record_link (tenant_id, junction, left_id, right_id)
    where deleted_at is null;
create index object_record_link_reverse on object_record_link (tenant_id, junction, right_id, left_id)
    where deleted_at is null;
