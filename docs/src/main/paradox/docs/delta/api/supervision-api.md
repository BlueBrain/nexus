# Supervision

This endpoint returns information about the projections running on the current node.

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

Indicates whether there are any unhealthy projects.

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

Attempts to heal a project.

**Example**

Request
:   @@snip [project-heal.sh](assets/supervision/project-heal.sh)

Response
:   @@snip [project-heal.json](assets/supervision/project-heal.json)
