-------------------------------------------------
-- Add empty remoteContexts field to resources --
-------------------------------------------------
UPDATE public.scoped_events
SET value = value || '{"remoteContexts": []}'
WHERE type = 'resource'
AND value ->> 'remoteContexts' is null
AND value ->> '@type' in ('ResourceCreated', 'ResourceUpdated', 'ResourceRefreshed');

UPDATE public.scoped_states
SET value = value || '{"remoteContexts": []}'
WHERE type = 'resource'
AND value ->> 'remoteContexts' is null

