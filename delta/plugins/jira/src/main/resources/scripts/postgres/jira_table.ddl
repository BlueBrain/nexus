CREATE TABLE IF NOT EXISTS public.jira_tokens (
    realm text,
    subject text,
    instant timestamptz,
    token_value JSONB,
    PRIMARY KEY (realm, subject)
);