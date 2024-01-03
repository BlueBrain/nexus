--
-- Table for the errors that occurred during scope initialization
--
CREATE TABLE IF NOT EXISTS public.scope_initialization_errors(
    ordering bigserial,
    type     text         NOT NULL,
    org      text         NOT NULL,
    project  text         NOT NULL,
    message  text         NOT NULL,
    instant  timestamptz  DEFAULT NOW(),
    PRIMARY KEY(ordering)
);