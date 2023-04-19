DROP TABLE    IF EXISTS public.global_events;
DROP TABLE    IF EXISTS public.global_states;
DROP TABLE    IF EXISTS public.scoped_tombstones;
DROP TABLE    IF EXISTS public.scoped_events;
DROP TABLE    IF EXISTS public.scoped_states;
DROP TABLE    IF EXISTS public.ephemeral_states;
DROP TABLE    IF EXISTS public.entity_dependencies;
DROP TABLE    IF EXISTS public.projection_restarts;
DROP TABLE    IF EXISTS public.composite_restarts;
DROP TABLE    IF EXISTS public.composite_offsets;
DROP TABLE    IF EXISTS public.projection_offsets;
DROP TABLE    IF EXISTS public.failed_elem_logs;
DROP TABLE    IF EXISTS public.deleted_project_reports;
DROP TABLE    IF EXISTS public.blazegraph_queries;
DROP SEQUENCE IF EXISTS public.event_offset;
DROP SEQUENCE IF EXISTS public.state_offset;