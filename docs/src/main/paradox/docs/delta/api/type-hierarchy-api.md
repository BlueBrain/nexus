# Type hierarchy

The type hierarchy is a singleton entity that defines a mapping between concrete types and their abstract types.

@@@ note { .tip title="Authorization notes" }

When modifying the type hierarchy, the caller must have `resources/read` permission at root level.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Create using POST

```
POST /v1/type-hierarchy
  {...}
```

**Example**

Request
:   @@snip [create.sh](assets/type-hierarchy/create.sh)

Payload
:   @@snip [payload.json](assets/type-hierarchy/payload.json)

Response
:   @@snip [created.json](assets/type-hierarchy/created.json)

## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the resource, the last revision needs to be passed as a query parameter.

```
PUT /v1/type-hierarchy?rev={previous_rev}
  {...}
```

... where `{previous_rev}` is the last known revision number for the schema.

**Example**

Request
:   @@snip [update.sh](assets/type-hierarchy/update.sh)

Payload
:   @@snip [payload.json](assets/type-hierarchy/payload.json)

Response
:   @@snip [updated.json](assets/type-hierarchy/updated.json)

## Fetch

```
GET /v1/type-hierarchy
```

**Example**

Request
:   @@snip [schema-fetch.sh](assets/type-hierarchy/fetch.sh)

Response
:   @@snip [schema-fetched.json](assets/type-hierarchy/fetched.json)