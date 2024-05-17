CREATE TABLE IF NOT EXISTS public.ship_original_project_context(
    org      text         NOT NULL,
    project  text         NOT NULL,
    context  JSONB        NOT NULL,
    PRIMARY KEY(org, project)
)

