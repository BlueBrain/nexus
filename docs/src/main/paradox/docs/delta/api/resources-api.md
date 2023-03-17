# Resources

Generic resources are rooted in the `/v1/resources/{org_label}/{project_label}/{schema_id}` collection.

Each resource... 

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}` 
- it is validated against a `schema` with id `{schema_id}`. In case of using `_` for this segment, the schema segment 
  reads as `irrelevant`.

@@@ note { .tip title="Authorization notes" }	

When  modifying resources, the caller must have `resources/write` permissions on the current path of the project or the 
ancestor paths.

When  reading resources, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

@@@ note { .warning }

From Delta v1.5, remote contexts are only resolved during creates and updates.
That means that when those get updated, the resources importing them must be also updated to take them into account the new version.

@@@

## Indexing

All the API calls modifying a resource (creation, update, tagging, deprecation) can specify whether the resource should be indexed 
synchronously or in the background. This behaviour is controlled using `indexing` query param, which can be one of two values:

  - `async` - (default value) the resource will be indexed asynchronously 
  - `sync` - the resource will be indexed synchronously and the API call won't return until the indexing is finished

## Create using POST

```
POST /v1/resources/{org_label}/{project_label}/{schema_id}
  {...}
```

The json payload: 

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is the 
  `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [create.sh](assets/resources/create.sh)

Payload
:   @@snip [payload.json](assets/resources/payload.json)

Response
:   @@snip [created.json](assets/resources/created.json)


## Create using PUT

This alternative endpoint to create a resource is useful in case the json payload does not contain an `@id` but you want 
to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{resource_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](assets/resources/create-put.sh)

Payload
:   @@snip [payload.json](assets/resources/payload.json)

Response
:   @@snip [created.json](assets/resources/created.json)


## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the resource, the last revision needs to be passed as a query parameter.

```
PUT /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={previous_rev}
  {...}
```
... where `{previous_rev}` is the last known revision number for the resource.


**Example**

Request
:   @@snip [update.sh](assets/resources/update.sh)

Payload
:   @@snip [payload.json](assets/resources/payload.json)

Response
:   @@snip [updated.json](assets/resources/updated.json)


## Refresh

This operation refreshes the compacted and expanded representations of the resource.

This is equivalent of doing an update with the same source as the last revision of the resource. It is useful when the 
schema or project contexts have changed, in order for the changes to be reflected in the resource.

```
PUT /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/refresh
```

**Example**

Request
:   @@snip [refresh.sh](assets/resources/refresh.sh)

Response
:   @@snip [refreshed.json](assets/resources/updated.json)


## Validate

This operation runs validation of a resource against a schema. This would be useful to test whether resources would
match the shape of a new schema. 

This is equivalent of doing an update with the same source as the last revision of the resource. It is useful when the
schema or project contexts have changed, in order for the changes to be reflected in the resource.

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/validate
```

**Example**

Request
:   @@snip [validate.sh](assets/resources/validate.sh)

Response
:   @@snip [validated.json](assets/resources/validated.json)

## Tag

Links a resource revision to a specific name. 

Tagging a resource is considered to be an update as well.

```
POST /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{previous_rev}`: is the last known revision number for the resource.
- `{name}`: String - label given to the resources at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [tag.sh](assets/resources/tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [tagged.json](assets/resources/tagged.json)

## Remove tag

Removes a given tag.

Removing a tag is considered to be an update as well.

```
DELETE /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/tags/{tag_name}?rev={previous_rev}
```
... where

- `{previous_rev}`: is the last known revision number for the resource.
- `{tag_name}`: String - label of the tag to remove.

**Example**

Request
:   @@snip [tag.sh](assets/resources/delete-tag.sh)

Response
:   @@snip [tagged.json](assets/resources/tagged.json)


## Deprecate

Locks the resource, so no further operations can be performed. It also deletes the resource from listing/querying results.

Deprecating a resource is considered to be an update as well. 

```
DELETE /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.

**Example**

Request
:   @@snip [deprecate.sh](assets/resources/deprecate.sh)

Response
:   @@snip [deprecated.json](assets/resources/deprecated.json)

## Fetch

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch.sh](assets/resources/fetch.sh)

Response
:   @@snip [fetched.json](assets/resources/fetched.json)

If the @ref:[redirect to Fusion feature](../../getting-started/running-nexus/configuration/index.md#fusion-configuration) is enabled and
if the `Accept` header is set to `text/html`, a redirection to the fusion representation of the resource will be returned.

## Fetch original payload

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/source?rev={rev}&tag={tag}&annotate={annotate}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
- `{annotate}`: Boolean - annotate the response with the resource metadata. This field only applies to standard resources. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

If `{annotate}` is set, fields present in the metadata will override fields with the same name from the payload. The `@id` field is an exception to this rule

**Example**

Request
:   @@snip [fetchSource.sh](assets/resources/fetchSource.sh)

Response
:   @@snip [fetched.json](assets/resources/payload.json)

## Fetch tags

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/tags?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchTags.sh](assets/resources/tags.sh)

Response
:   @@snip [tags.json](assets/tags.json)

## List

There are three available endpoints to list resources in different scopes.

### Within a project

```
GET /v1/resources/{org_label}/{project_label}?from={from}
                                             &size={size}
                                             &deprecated={deprecated}
                                             &rev={rev}
                                             &type={type}
                                             &createdBy={createdBy}
                                             &updatedBy={updatedBy}
                                             &schema={schema}
                                             &q={search}
                                             &sort={sort}
```

### Within an organization

This operation returns only resources from projects defined in the organisation `{org_label}` and where the caller has the `resources/read` permission.

```
GET /v1/resources/{org_label}?from={from}
                             &size={size}
                             &deprecated={deprecated}
                             &rev={rev}
                             &type={type}
                             &createdBy={createdBy}
                             &updatedBy={updatedBy}
                             &schema={schema}
                             &q={search}
                             &sort={sort}
```

### Within all projects

This operation returns only resources from projects where the caller has the `resources/read` permission.

```
GET /v1/resources?from={from}
                 &size={size}
                 &deprecated={deprecated}
                 &rev={rev}
                 &type={type}
                 &createdBy={createdBy}
                 &updatedBy={updatedBy}
                 &schema={schema}
                 &q={search}
                 &sort={sort}
```

### Parameter description

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting resources based on their revision value
- `{type}`: Iri - can be used to filter the resulting resources based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting resources based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting resources based on the person which performed the last update
- `{schema}`: Iri - can be used to filter the resulting resources based on the conformant schema
- `{search}`: String - can be provided to select only the resources in the collection that have attribute values 
  matching (containing) the provided string
- `{sort}`: String - can be used to sort resources based on a payloads' field. This parameter can appear multiple times 
  to enable sorting by multiple fields. The default is done by `_createdBy` and `@id`.


**Example**

Request
:   @@snip [list.sh](assets/resources/list.sh)

Response
:   @@snip [listed.json](assets/resources/listed.json)


## List filtering by schema

This operation is only available at the project scope.

### Within a project

```
GET /v1/resources/{org_label}/{project_label}/{schemaId}?from={from}
                                                        &size={size}
                                                        &deprecated={deprecated}
                                                        &rev={rev}&type={type}
                                                        &createdBy={createdBy}
                                                        &updatedBy={updatedBy}
```

### Parameter description

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting resources based on their revision value
- `{type}`: Iri - can be used to filter the resulting resources based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting resources based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting resources based on the person which performed the last update

**Example**

Request
:   @@snip [schema-list.sh](assets/resources/schema-list.sh)

Response
:   @@snip [listed.json](assets/resources/listed.json)

## List incoming links

Provides a list of resources where the current resource `{resource_id}` is being referenced in the payload.

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/incoming
                  ?from={from}
                  &size={size}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`

**Example**

Request
:   @@snip [incoming.sh](assets/resources/incoming.sh)

Response
:   @@snip [incoming.json](assets/resources/incoming.json)

## List outgoing links

Provides a list of resources that are being used in the current resource `{resource_id}` payload. It also offers information 

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/outgoing
                  ?from={from}
                  &size={size}
                  &includeExternalLinks={includeExternalLinks}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{includeExternalLinks}`: Boolean - flag to decide whether or not external links are to be included. External links 
  are references to resources in other projects, or even resources external to Nexus; defaults to `true`

**Example**

Request
:   @@snip [outgoing.sh](assets/resources/outgoing.sh)

Response
:   @@snip [outgoing.json](assets/resources/outgoing.json)

## Server Sent Events

```
GET /v1/resources/events                              # for all resource events in the application
GET /v1/resources/{org_label}/events                  # for resource events in the given organization
GET /v1/resources/{org_label}/{project_label}/events  # for resource events in the given project
```

The caller must have respectively the `events/read` permission on `/`, `{org_label}` and `{org_label}/{project_label}`.

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `{project_label}`: String - the selected project for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

The server sent events response contains a series of resource events, represented in the following way

```
data:{payload}
event:{type}
id:{id}
```

where...

- `{payload}`: Json - is the actual payload of the current resource
- `{type}`: String - is a type identifier for the current resource. Possible types are related to core resource types (Resouce, Schema, Resolver) and available plugin types
- `{id}`: String - is the identifier of the resource event. It can be used in the `Last-Event-Id` query parameter


**Example**

Request
:   @@snip [sse.sh](assets/resources/sse.sh)

Response
:   @@snip [sse.json](assets/resources/sse.json)
