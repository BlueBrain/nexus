CREATE TABLE IF NOT EXISTS public.flattened_acls(
    address     text            NOT NULL,
    identity    text            NOT NULL,
    permission  text            NOT NULL,
    PRIMARY KEY(identity, permission, address)
);

CREATE INDEX IF NOT EXISTS flattened_acls_address_idx ON public.flattened_acls(address);

