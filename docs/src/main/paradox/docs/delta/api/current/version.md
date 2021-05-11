# Version endpoint

```http request
GET /v1/version
```

This endpoint returns information about the running Delta instance: Delta version, plugin versions and service dependency versions(e.g Cassandra).

Example
:   @@snip [version.json](assets/version.json)

@@@ note { .tip title="Authorization notes" }

When accessing the version endpoint, the caller must have `version/read` permission `/`.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@