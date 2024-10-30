# Supervision

Those endpoints return information which helps to monitor and investigate problems in Nexus.

To access those endpoints, the `supervision/read` permission must be granted at the root level.

## Fetch projections

```
GET /v1/supervision/projections
```

Fetches the projections running on the current node.

**Example**

Request
:   @@snip [supervision-list.sh](assets/supervision/supervision-list.sh)

Response
:   @@snip [supervision-list.json](assets/supervision/supervision-list.json)

## Projects health

```
GET /v1/supervision/projects
```

Indicates whether there are any unhealthy projects. A project is considered healthy if it has been correctly provisioned
on creation.

**Example**

Request
:   @@snip [projects-health.sh](assets/supervision/projects-health.sh)

Response (healthy)
:   @@snip [projects-health.json](assets/supervision/projects-health.json)

Response (unhealthy)
:   @@snip [projects-health-unhealthy.json](assets/supervision/projects-health-unhealthy.json)

## Project healing

```
POST /v1/supervision/projects/{orgLabel}/{projectLabel}/heal
```

Attempts to heal a project. This will attempt to run again the provisioning process for the project.

**Example**

Request
:   @@snip [project-heal.sh](assets/supervision/project-heal.sh)

Response
:   @@snip [project-heal.json](assets/supervision/project-heal.json)

## Blazegraph

This endpoint allows to return the total number of triples for all blazegraph views 
and the number of triples per individual view.

The unassigned part allows to spot orphan namespaces, that is to say namespaces which may not have been properly deleted
when a view got modified or deprecated.

This is an indicator which helps to scale correctly the @ref:[Blazegraph instance](../../getting-started/running-nexus/blazegraph.md).

```
GET /v1/supervision/blazegraph
```

Request
:   @@snip [supervision-blazegraph.sh](assets/supervision/supervision-blazegraph.sh)

Response
:   @@snip [supervision-blazegraph.json](assets/supervision/supervision-blazegraph.json)

## Composite views

This endpoint serves the same purpose but for common namespaces of composite views.

```
GET /v1/supervision/composite-views
```