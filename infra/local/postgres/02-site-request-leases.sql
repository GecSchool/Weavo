CREATE SCHEMA IF NOT EXISTS weavo_site_access;

CREATE TABLE IF NOT EXISTS weavo_site_access.host_request (
    host text PRIMARY KEY,
    owner uuid,
    not_before timestamptz NOT NULL DEFAULT '-infinity'
);
