# Resources

Generic resources are rooted in the `/v1/resources/{org_label}/{project_label}/{schema_id}` collection.

Each resource... 

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}` 
- it is validated against a `schema` with id `{schema_id}`. In case of using `_` for this segment, the schema segment 
  reads as `irrelevant`.

Access to resources in the system depends on the access control list set for them. Depending on the access control list, 
a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn more about how 
to retrieve an access token.

@@@ note { .tip title="Authorization notes" }	

When  modifying resources, the caller must have `resources/write` permissions on the current path of the project or the 
ancestor paths.

When  reading resources, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

@@@

## Create a resource using POST

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
:   @@snip [resource.sh](assets/resources/resource.sh)

Payload
:   @@snip [resource.json](assets/resources/resource.json)

Response
:   @@snip [resource-ref-new.json](assets/resources/resource-ref-new.json)


## Create a resource using PUT

This alternative endpoint to create a resource is useful in case the json payload does not contain an `@id` but you want 
to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{resource_id}`, the request will fail.

**Example**

Request
:   @@snip [resource-put.sh](assets/resources/resource-put.sh)

Payload
:   @@snip [resource.json](assets/resources/resource.json)

Response
:   @@snip [resource-ref-new.json](assets/resources/resource-ref-new.json)


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
:   @@snip [resource-update.sh](assets/resources/resource-update.sh)

Payload
:   @@snip [resource.json](assets/resources/resource.json)

Response
:   @@snip [resource-ref-new-updated.json](assets/resources/resource-ref-new-updated.json)


## Tag a resource

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
:   @@snip [resource-tag.sh](assets/resources/resource-tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [resource-ref-new-tagged.json](assets/resources/resource-ref-new-tagged.json)


## Deprecate a resource

Locks the resource, so no further operations can be performed. It also deletes the resource from listing/querying results.

Deprecating a resource is considered to be an update as well. 

```
DELETE /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.

**Example**

Request
:   @@snip [resource-deprecate.sh](assets/resources/resource-deprecate.sh)

Response
:   @@snip [resource-ref-new-deprecated.json](assets/resources/resource-ref-new-deprecated.json)

## Fetch a resource

When fetching a resource, the response format can be chosen through HTTP content negotiation, using the **Accept** HTTP 
header.

- **application/ld+json**: JSON-LD output response. Further specifying the query parameter `format=compacted|expanded` 
  will provide with the JSON-LD @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } 
  or the @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **application/n-triples**: RDF n-triples response, as defined by the @link:[w3](https://www.w3.org/TR/n-triples/){ open=new }.
- **text/vnd.graphviz**: A @link:[DOT response](https://www.graphviz.org/doc/info/lang.html){ open=new }.

If `Accept: */*` HTTP header is present, Nexus defaults to the JSON-LD output in compacted form.

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [resource-fetch.sh](assets/resources/resource-fetch.sh)

Response
:   @@snip [resource-fetched.json](assets/resources/resource-fetched.json)

## Fetch a resource original payload

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/source?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [resource-fetch.sh](assets/resources/resource-fetch-source.sh)

Response
:   @@snip [resource-fetched.json](assets/resources/resource-fetched-source.json)

## List resources

```
GET /v1/resources/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&schema={schema}&q={search}&sort={sort}
```
                                          
where...

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
  to enable sorting by multiple fields


**Example**

Request
:   @@snip [resources-list.sh](assets/resources/resources-list.sh)

Response
:   @@snip [resources-list.json](assets/resources/resources-list.json)


## List resources belonging to a schema

```
GET /v1/resources/{org_label}/{project_label}/{schemaId}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}
```

where...

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
:   @@snip [resources-schema-list.sh](assets/resources/resources-schema-list.sh)

Response
:   @@snip [resources-list.json](assets/resources/resources-list.json)

## List incoming links

Provides a list of resources where the current resource `{resource_id}` is being referenced in the payload.

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/incoming?from={from}&size={size}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`

**Example**

Request
:   @@snip [resources-incoming.sh](assets/resources/incoming.sh)

Response
:   @@snip [resources-incoming.json](assets/resources/incoming.json)

## List outgoing links

Provides a list of resources that are being used in the current resource `{resource_id}` payload. It also offers information 

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/outgoing?from={from}&size={size}&includeExternalLinks={includeExternalLinks}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{includeExternalLinks}`: Boolean - flag to decide whether or not external links are to be included. External links 
  are references to resources in other projects, or even resources external to Nexus; defaults to `true`

**Example**

Request
:   @@snip [resources-outgoing.sh](assets/resources/outgoing.sh)

Response
:   @@snip [resources-outgoing.json](assets/resources/outgoing.json)

## Resources Server Sent Events

This endpoint allows clients to receive automatic updates from the realms in a streaming fashion.

The server sent events response contains a series of resource events, represented in the following way

```
data:{payload}
event:{type}
id:{id}
```

where...

- `{payload}`: Json - is the actual payload of the current resource
- `{type}`: String - is a type identifier for the current realm. Possible types are: Created, Updated, Deprecated, 
  TagAdded, FileCreated, FileUpdated
- `{id}`: String - is the identifier of the resource event. It can be used in the `Last-Event-Id` query parameter


### Server Sent Events all resources


```
GET /v1/resources/events
```

where `Last-Event-Id` is an optional HTTP Header that identifies the last consumed resource event. It can be used for 
cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

**Example**

Request
:   @@snip [resources-event-all.sh](assets/resources/event-all.sh)

Response
:   @@snip [resources-event-all.json](assets/resources/event-all.json)


### Server Sent Events organization resources

```
GET /v1/resources/{org_label}/events
```

where 

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for 
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

**Example**

Request
:   @@snip [resources-event-org.sh](assets/resources/event-org.sh)

Response
:   @@snip [resources-event-org.json](assets/resources/event-org.json)

### Server Sent Events project resources

```
GET /v1/resources/{org_label}/{project_label}/events
```

where 

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `{project_label}`: String - the selected project for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for 
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

**Example**

Request
:   @@snip [resources-event-project.sh](assets/resources/event-project.sh)

Response
:   @@snip [resources-event-project.json](assets/resources/event-project.json)