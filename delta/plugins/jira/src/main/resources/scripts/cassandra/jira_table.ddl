CREATE TABLE IF NOT EXISTS {{keyspace}}.jira_tokens (
    realm text,
    subject text,
    instant bigint,
    token_value text,
    PRIMARY KEY ((realm, subject)));