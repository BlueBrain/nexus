# Quotas

Quotas are rooted in /v1/quotas collection.

Each quota defines the maximum number of resources and events that can exist in a certain scope.

Quotas are powered through static configuration (`app.projects.quotas`) and they cannot be modified at runtime.

@@@ note { .tip title="Authorization notes" }

When accessing the quotas endpoint, the caller must have `quotas/read` permissions on the current path of the project or the ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Fetch

```http request
GET /v1/quotas/{org_label}/{project_label}
```

This endpoint returns information about the quotas configuration for the target project.



**Example**

Request
:   @@snip [fetch-quotas.sh](assets/quotas/fetch-quotas.sh)

Response
:   @@snip [fetched-quotas.json](assets/quotas/fetched-quotas.json)

@@@ note { .warning }

This resource endpoint is experimental and the response structure might change in the future.

@@@