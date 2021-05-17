# Resolvers

Resolvers are rooted in the `/v1/resolvers/{org_label}/{project_label}` collection and are used in the following 
scenarios:

- Bring the content of the `owl:imports` predicate for schema resources. The value is the `@id` of the resource. E.g.: 
You can define owl imports on a schema, as follows `"owl:imports": "http://example.com/myid"`. The resolver will try to 
find a resource with `"@id": "http://example.com/myid"` and if found, will bring the payload into the original resource. 
- Bring the content of the `@context` links. The value is the `@id` of the resource. E.g.: A resource might define the 
context as follows: `"@context": "http://example.com/id"`. The resolver will try to find a resource with 
`"@id": "http://example.com/id"` and if found, will bring the payload into the original resource. 

Each resolver belongs to a `project` identifier by the label `{project_label}` inside an `organization` identifier by the label `{org_label}`.

@@@ note { .tip title="Authorization notes" }	

When  modifying resolvers, the caller must have `resolvers/write` permissions on the current path of the project or the 
ancestor paths.

When  reading resolvers, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Resolver types

There are several types of resolvers, which perform resolution in different scopes.

### InProject resolver

The scope of the resolution is the current project where the resource resides. In other words:

- Schema `A` can import schema `B` using the `owl:imports` as long as schema `B` is located on the same project as 
  schema `A`. 
- Resource `A` can reference to a remote context existing in resource `B` as long as resource `B` is located on the same 
  project as resource `A`. 

This resolver gets automatically created when the project is created and has the highest priority for resolution.
It should not be modified.

**InProject resolver payload**

```json
{
    "@id": "https://bluebrain.github.io/nexus/vocabulary/defaultInProject",
    "@type": "InProject",
    "priority": {priority},
}
```

where `{priority}` is a numeric value (from 0 - 1000) which defines the resolution priority when attempting to find the 
resource with a particular @id.

### CrossProject resolver

The scope of the resolution is the collections of projects `P` defined on the resolver. CrossProject resolution also 
defines a identity policy `I` (via the `identities` or the `useCurrentCaller` fields) to enforce ACLs. In other words:

- Schema `A` can import schema `B` using the `owl:imports` as long as schema `B` is located in some of the projects from 
  the collection `P` and as long `I` have `resources/read` permissions on the schema `B` project.
- Resource `A` can reference to a remote context existing in resource `B` as long as resource `B` is located in some of 
  the projects from the collection `P` and as long as `I` have `resources/read` permissions on the schema `B` project.


**CrossProject resolver payload**

```json
{
  "@id": "{someId}",
  "@type": ["Resolver", "CrossProject"],
  "priority": {priority}
  "resourceTypes": ["{resourceType}", ...],
  "projects": ["{project}", ... ],
  "identities": [ {identity}, {...} ],
  "useCurrentCaller": {useCurrentCaller},
}
```

where...

- `{someId}`: Iri - the @id value for this resolver.
- `{priority}`: Number - value (from 0 - 1000) which defines the resolution priority when attempting to find the resource. All resolvers must have a different priority in a same project
- `{resourceType}`: Iri - resolves only the resources with `@type` containing `{resourceType}`. This field is optional.
  with a particular @id.
- `{project}`: String - the user friendly reference to the project from where the resolution process will attempt to 
  find the @id's. It follows the format `{organization}/{project}`.
- `{identity}`: Json object - the identity against which to enforce ACLs during resolution process. Can't be defined if `useCurrentCaller` is set to `true`
- `{useCurrentCaller}`: Boolean - the resolution process will use the caller and its identitites to enforce acls. Can't be `true` when `_identity_` is defined.

## Create using POST

```
POST /v1/resolvers/{org_label}/{project_label}
  {...}
```

The json payload: 

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is 
  the `prefix` defined on the resolver's project (`{project_label}`).

**Example**

Request
:   @@snip [create.sh](assets/resolvers/create.sh)

Payload
:   @@snip [payload.json](assets/resolvers/payload.json)

Response
:   @@snip [created.json](assets/resolvers/created.json)


## Create using PUT
This alternative endpoint to create a resolver is useful in case the json payload does not contain an `@id` but you 
want to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/resolvers/{org_label}/{project_label}/{resolver_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{resolver_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](assets/resolvers/create-put.sh)

Payload
:   @@snip [payload.json](assets/resolvers/payload.json)

Response
:   @@snip [created.json](assets/resolvers/created.json)


## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resolver without having had seen the previous revision of
the resolver, the last revision needs to be passed as a query parameter.

```
PUT /v1/resolvers/{org_label}/{project_label}/{resolver_id}?rev={previous_rev}
  {...}
```
... where `{previous_rev}` is the last known revision number for the resolver.


**Example**

Request
:   @@snip [update.sh](assets/resolvers/update.sh)

Payload
:   @@snip [payload.json](assets/resolvers/payload.json)

Response
:   @@snip [updated.json](assets/resolvers/updated.json)


## Tag

Links a resolver revision to a specific name. 

Tagging a resolver is considered to be an update as well.

```
POST /v1/resolvers/{org_label}/{project_label}/{resolver_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{previous_rev}`: Number - the last known revision for the resolver.
- `{name}`: String - label given to the resolver at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [tag.sh](assets/resolvers/tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [tagged.json](assets/resolvers/tagged.json)


## Deprecate

Locks the resolver, so no further operations can be performed. It will also not be taken into account in the resolution process.

Deprecating a resolver is considered to be an update as well. 

```
DELETE /v1/resolvers/{org_label}/{project_label}/{resolver_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resolver.

**Example**

Request
:   @@snip [deprecate.sh](assets/resolvers/deprecate.sh)

Response
:   @@snip [resolver-ref-deprecated.json](assets/resolvers/deprecated.json)


## Fetch

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch.sh](assets/resolvers/fetch.sh)

Response
:   @@snip [fetched.json](assets/resolvers/fetched.json)


## Fetch original payload

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}/source?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchSource.sh](assets/resolvers/fetchSource.sh)

Response
:   @@snip [payload.json](assets/resolvers/payload.json)

## Fetch tags

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}/tags?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchTags.sh](assets/resolvers/tags.sh)

Response
:   @@snip [tags.json](assets/tags.json)

## List

```
GET /v1/resolvers/{org_label}/{project_label}?from={from}
                                             &size={size}
                                             &deprecated={deprecated}
                                             &rev={rev}
                                             &type={type}
                                             &createdBy={createdBy}
                                             &updatedBy={updatedBy}
                                             &q={search}
                                             &sort={sort}
```
                                          
where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resolvers based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting resolvers based on their revision value
- `{type}`: Iri - can be used to filter the resulting resolvers based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting resolvers based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting resolvers based on the person which performed the last update
- `{search}`: String - can be provided to select only the resolvers in the collection that have attribute values 
  matching (containing) the provided string
- `{sort}`: String - can be used to sort resolvers based on a payloads' field. This parameter can appear multiple times 
  to enable sorting by multiple fields. The default is done by `_createdBy` and `@id`.

**Example**

Request
:   @@snip [list.sh](assets/resolvers/list.sh)

Response
:   @@snip [listed.json](assets/resolvers/listed.json)


## Fetch resource using resolvers

Fetches a resource using the provided resolver. 

If the resolver segment (`{resolver_id}`) is `_` the resource is fetched from the first resolver in the requested 
project (`{org_label}/{project_label}`). The resolvers are ordered by its priority field.

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}/{resource_id}?rev={rev}
                                                                         &tag={tag}
                                                                         &showReport={showReport}
```
... where

- `{resource_id}`: Iri - the @id value of the resource to be retrieved.
- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
- `{showReport}`: Boolean - return the resolver resolution steps instead of the resource for debugging purposes.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch-resource.sh](assets/resolvers/fetch-resource.sh)

Response
:   @@snip [fetched.json](assets/resources/fetched.json)

## Server Sent Events

From Delta 1.5, it is possible to fetch SSEs for all resolvers or just resolvers
in the scope of an organization or a project.

```
GET /v1/resolvers/events # for all resolver events in the application
GET /v1/resolvers/{org_label}/events # for resolver events in the given organization
GET /v1/resolvers/{org_label}/{project_label}/events # for resolver events in the given project
```

The caller must have respectively the `events/read` permission on `/`, `{org_label}` and `{org_label}/{project_label}`.

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `{project_label}`: String - the selected project for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

@@@ note { .warning }

The event type for resolvers SSEs have been changed so that it is easier to distinguish them from other types of resources.

@@@

**Example**

Request
:   @@snip [resolvers-sse.sh](assets/resolvers/sse.sh)

Response
:   @@snip [resolvers-sse.json](assets/resolvers/sse.json)
