# Schemas

Schemas are rooted in the `/v1/schemas/{org_label}/{project_label}` collection. They define a set of rules and 
constraints using @link:[SHACL](https://www.w3.org/TR/shacl/){ open=new }. Once those schemas are present, other 
resources can be created against them. Those resources won't be successfully created unless they match the required 
constraints defined on the schema.

Each schema... 

- belongs to a `project` identifier by the label `{project_label}` 
- inside an `organization` identifier by the label `{org_label}` 
- it is validated against the @link:[SHACL schema](https://bluebrainnexus.io/schemas/shacl-20170720.ttl){ open=new } 
  (version 20170720).
  

@@@ note { .tip title="Authorization notes" }	

When modifying schemas, the caller must have `schemas/write` permissions on the current path of the project or the 
ancestor paths.

When reading schemas, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

@@@ note { .warning }

From Delta v1.5, remote contexts and `owl:imports` are only resolved during creates and updates.
That means that when those get updated, the schemas importing them must be also updated to take the changes into account.

@@@

## Indexing

All the API calls modifying a schema (creation, update, tagging, deprecation) can specify whether the schema should be indexed
synchronously or in the background. This behaviour is controlled using `indexing` query param, which can be one of two values:

- `async` - (default value) the schema will be indexed asynchronously
- `sync` - the schema will be indexed synchronously and the API call won't return until the indexing is finished

## Create using POST

```
POST /v1/schemas/{org_label}/{project_label}
  {...}
```

The json payload: 

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is the 
  `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [create.sh](assets/schemas/create.sh)

Payload
:   @@snip [payload.json](assets/schemas/payload.json)

Response
:   @@snip [created.json](assets/schemas/created.json)


## Create using PUT

This alternative endpoint to create a schema is useful in case the json payload does not contain an `@id` but you want 
to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/schemas/{org_label}/{project_label}/{schema_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{schema_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](assets/schemas/create-put.sh)

Payload
:   @@snip [payload.json](assets/schemas/payload.json)

Response
:   @@snip [created.json](assets/schemas/created.json)


## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the resource, the last revision needs to be passed as a query parameter.

```
PUT /v1/schemas/{org_label}/{project_label}/{schema_id}?rev={previous_rev}
  {...}
```
... where `{previous_rev}` is the last known revision number for the schema.


**Example**

Request
:   @@snip [update.sh](assets/schemas/update.sh)

Payload
:   @@snip [payload.json](assets/schemas/payload.json)

Response
:   @@snip [updated.json](assets/schemas/updated.json)


## Tag

Links a schema revision to a specific name. 

Tagging a schema is considered to be an update as well.

```
POST /v1/schemas/{org_label}/{project_label}/{schema_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{previous_rev}`: Number - is the last known revision for the resolver.
- `{name}`: String - label given to the schemas at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [tag.sh](assets/schemas/tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [tagged.json](assets/schemas/tagged.json)

## Remove tag

Removes a given tag.

Removing a tag is considered to be an update as well.

```
DELETE /v1/schemas/{org_label}/{project_label}/{schema_id}/tags/{tag_name}?rev={previous_rev}
```
... where

- `{previous_rev}`: is the last known revision number for the resource.
- `{tag_name}`: String - label of the tag to remove.

**Example**

Request
:   @@snip [tag.sh](assets/schemas/delete-tag.sh)

Response
:   @@snip [tagged.json](assets/schemas/tagged.json)

## Deprecate

Locks the schema, so no further operations can be performed. It also deletes the schema from listing/querying results.

Deprecating a schema is considered to be an update as well. 

```
DELETE /v1/schemas/{org_label}/{project_label}/{schema_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the schema.

**Example**

Request
:   @@snip [deprecate.sh](assets/schemas/deprecate.sh)

Response
:   @@snip [deprecated.json](assets/schemas/deprecated.json)

## Fetch

```
GET /v1/schemas/{org_label}/{project_label}/{schema_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [schema-fetch.sh](assets/schemas/fetch.sh)

Response
:   @@snip [schema-fetched.json](assets/schemas/fetched.json)

If the @ref:[redirect to Fusion feature](../../getting-started/running-nexus/configuration/index.md#fusion-configuration) is enabled and
if the `Accept` header is set to `text/html`, a redirection to the fusion representation of the resource will be returned.

## Fetch original payload

```
GET /v1/schemas/{org_label}/{project_label}/{schema_id}/source?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchSource.sh](assets/schemas/fetchSource.sh)

Response
:   @@snip [payload.json](assets/schemas/payload.json)

## Fetch tags

```
GET /v1/schemas/{org_label}/{project_label}/{schema_id}/tags?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchTags.sh](assets/schemas/tags.sh)

Response
:   @@snip [tags.json](assets/tags.json)

## List

There are three available endpoint to list schemas in different scopes.

### Within a project

```
GET /v1/schemas/{org_label}/{project_label}?from={from}
                                           &size={size}
                                           &deprecated={deprecated}
                                           &rev={rev}
                                           &type={type}
                                           &createdBy={createdBy}
                                           &updatedBy={updatedBy}
                                           &q={search}
                                           &sort={sort}
```

### Within an organization

This operation returns only schemas from projects defined in the organisation `{org_label}` and where the caller has the `resources/read` permission.

```
GET /v1/schemas/{org_label}?from={from}
                           &size={size}
                           &deprecated={deprecated}
                           &rev={rev}
                           &type={type}
                           &createdBy={createdBy}
                           &updatedBy={updatedBy}
                           &q={search}
                           &sort={sort}
```

### Within all projects

This operation returns only schemas from projects defined the organisation `{org_label}` and where the caller has the `resources/read` permission.

```
GET /v1/schemas?from={from}
               &size={size}
               &deprecated={deprecated}
               &rev={rev}
               &type={type}
               &createdBy={createdBy}
               &updatedBy={updatedBy}
               &q={search}
               &sort={sort}
```

### Parameter description

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting schemas based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting schemas based on their revision value
- `{type}`: Iri - can be used to filter the resulting schemas based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting schemas based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting schemas based on the person which performed the last update
- `{search}`: String - can be provided to select only the schemas in the collection that have attribute values matching 
  (containing) the provided string
- `{sort}`: String - can be used to sort schemas based on a payloads' field. This parameter can appear multiple times 
  to enable sorting by multiple fields. The default is done by `_createdBy` and `@id`.


**Example**

Request
:   @@snip [list.sh](assets/schemas/list.sh)

Response
:   @@snip [listed.json](assets/schemas/listed.json)

## Server Sent Events

From Delta 1.5, it is possible to fetch SSEs for all schemas or just schemas
in the scope of an organization or a project.

```
GET /v1/schemas/events                              # for all schema events in the application
GET /v1/schemas/{org_label}/events                  # for schema events in the given organization
GET /v1/schemas/{org_label}/{project_label}/events  # for schema events in the given project
```

The caller must have respectively the `events/read` permission on `/`, `{org_label}` and `{org_label}/{project_label}`.

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `{project_label}`: String - the selected project for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

@@@ note { .warning }

The event type for schemas SSEs have been changed so that it is easier to distinguish them from other types of resources.

@@@

**Example**

Request
:   @@snip [schemas-sse.sh](assets/schemas/sse.sh)

Response
:   @@snip [schemas-sse.json](assets/schemas/sse.json)