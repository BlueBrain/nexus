# Resources

Generic resources are rooted in the `/v1/resources/{org_label}/{project_label}/{schema_id}` collection.

Each resource... 

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}` 
- it is validated against a `schema` (`{schema_id}`).

Any resources in the system might be protected using an **access token**, provided by the HTTP header `Authorization: Bearer {access_token}`. Visit @ref:[Authentication](../iam-service-api.md) in order to learn more about how to retrieve an access token.

## Create a resource using POST

```
POST /v1/resources/{org_label}/{project_label}/{schema_id}
  {...}
```

The json payload: 

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is the `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [resource.sh](../assets/resource.sh)

Payload
:   @@snip [resource.json](../assets/resource.json)

Response
:   @@snip [resource-ref-new.json](../assets/resource-ref-new.json)


## Create a resource using PUT
This alternative endpoint to create a resource is useful in case the json payload does not contain an `@id` but you want to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{resource_id}`, the request will fail.

**Example**

Request
:   @@snip [resource-put.sh](../assets/resource-put.sh)

Payload
:   @@snip [resource.json](../assets/resource.json)

Response
:   @@snip [resource-ref-new.json](../assets/resource-ref-new.json)


## Update a resource

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
:   @@snip [resource-update.sh](../assets/resource-update.sh)

Payload
:   @@snip [resource.json](../assets/resource.json)

Response
:   @@snip [resource-ref-new-updated.json](../assets/resource-ref-new-updated.json)


## Tag a resource

Links a resource revision to a specific name. 

Tagging a resource is considered to be an update as well.

```
PUT /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/tags?rev={previous_rev}
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
:   @@snip [resource-tag.sh](../assets/resource-tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [resource-ref-new-tagged.json](../assets/resource-ref-new-tagged.json)


## Deprecate a resource

Locks the resource, so no further operations can be performed. It also deletes the resource from listing/querying results.

Deprecating a resource is considered to be an update as well. 

```
DELETE /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.

**Example**

Request
:   @@snip [resource-deprecate.sh](../assets/resource-deprecate.sh)

Response
:   @@snip [resource-ref-new-deprecated.json](../assets/resource-ref-new-deprecated.json)


## Fetch a resource (current version)

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}
```

**Example**

Request
:   @@snip [resource-fetch.sh](../assets/resource-fetch.sh)

Response
:   @@snip [resource-fetched.json](../assets/resource-fetched.json)


## Fetch a resource (specific version)

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={rev}
```
... where `{rev}` is the revision number of the resource to be retrieved.

**Example**

Request
:   @@snip [resource-fetch-revision.sh](../assets/resource-fetch-revision.sh)

Response
:   @@snip [resource-fetched.json](../assets/resource-fetched.json)


## Fetch a resource (specific tag)

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?tag={tag}
```

... where `{tag}` is the tag of the resource to be retrieved.


**Example**

Request
:   @@snip [resource-fetch-tag.sh](../assets/resource-fetch-tag.sh)

Response
:   @@snip [resource-fetched-tag.json](../assets/resource-fetched-tag.json)


## List resources

```
GET /v1/resources/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&q={full_text_search_query}
```

where...

- `{full_text_search_query}`: String - can be provided to select only the resources in the collection that have attribute values matching (containing) the provided token; when this field is provided the results will also include score values for each result
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status


**Example**

Request
:   @@snip [resources-list.sh](../assets/resources-list.sh)

Response
:   @@snip [resources-list.json](../assets/resources-list.json)


## List resources belonging to a schema

```
GET /v1/resources/{org_label}/{project_label}/{schemaId}?from={from}&size={size}&deprecated={deprecated}&q={full_text_search_query}
```

where...

- `{full_text_search_query}`: String - can be provided to select only the resources in the collection that have attribute values matching (containing) the provided token; when this field is provided the results will also include score values for each result
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status


**Example**

Request
:   @@snip [resources-schema-list.sh](../assets/resources-schema-list.sh)

Response
:   @@snip [resources-list.json](../assets/resources-list.json)
