# Resolvers

Resolvers are rooted in the `/v1/resolvers/{org_label}/{project_label}` collection and are used in the following 
scenarios:

- Bring the content of the `owl:imports` predicate for schema resources. The value is the `@id` of the resource. E.g.: 
You can define owl imports on a schema, as follows `"owl:imports": "http://example.com/myid"`. The resolver will try to 
find a resource with `"@id": "http://example.com/myid"` and if found, will bring the payload into the original resource. 
- Bring the content of the `@context` links. The value is the `@id` of the resource. E.g.: A resource might define the 
context as follows: `"@context": "http://example.com/id"`. The resolver will try to find a resource with 
`"@id": "http://example.com/id"` and if found, will bring the payload into the original resource. 

Each resolver... 

- belongs to a `project` identifier by the label `{project_label}` 
- inside an `organization` identifier by the label `{org_label}` 
- it is validated against the [resolver schema](https://bluebrainnexus.io/schemas/resolver.json).

Access to resources in the system depends on the access control list set for them. Depending on the access control 
list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn more about how 
to retrieve an access token.

@@@ note { .tip title="Authorization notes" }	

When  modifying resolvers, the caller must have `resolvers/write` permissions on the current path of the project or the 
ancestor paths.

When  reading resolvers, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

@@@

## Resolver types

There are several types of resolvers, which perform resolution in different scopes.

### InProject resolver

The scope of the resolution is the current project where the resource resides. In other words:

- Schema `A` can import schema `B` using the `owl:import` as long as schema `B` is located on the same project as 
  schema `A`. 
- Resource `A` can reference resource's context `B` (inside `@context`) as long as resource `B` is located on the same 
  project as resource `A`. 

This resolver gets automatically created when the project is created and it cannot be modified.

**InProject resolver payload**

```json
{
    "@id": "nxv:InProject",
    "@type": [ "InProject", "Resolver" ],
    "priority": {priority},
}
```

where `{priority}` is a numeric value (from 0 - 1000) which defines the resolution priority when attempting to find the 
resource with a particular @id.

### CrossProject resolver

The scope of the resolution is the collections of projects `P` defined on the resolver. CrossProject resolution also 
defines a collection of identities `I` to enforce ACLs. In other words:

- Schema `A` can import schema `B` using the `owl:import` as long as schema `B` is located on some of the projects from 
  the collection `P` and as long `I` have `resources/read` permissions on the schema `B` project.
- Resource `A` can reference resource's context `B` (inside `@context`) as long as resource `B` is located on some of 
  the projects from the collection `P` and as long as `I` have `resources/read` permissions on the schema `B` project.


**CrossProject resolver payload**

```json
{
  "@id": "{someid}",
  "@type": ["Resolver", "CrossProject"],
  "resourceTypes": ["{resourceType}", ...],
  "projects": ["{project}", ... ],
  "identities": [ {_identity_}, {...} ],
  "priority": 50
}
```

where...

- `{resourceType}`: Iri - resolves only the resources with `@type` containing `{resourceType}`. This field is optional.
- `{priority}`: Number - value (from 0 - 1000) which defines the resolution priority when attempting to find the resource 
  with a particular @id.
- `{project}`: String - the user friendly reference to the project from where the resolution process will attempt to 
  find the @id's. It follows the format `{organization}/{project}`.
- `_identity_`: Json object - the identity against which to enforce ACLs during resolution process.
- `{someid}`: Iri - the @id value for this resolver.

**Example**

Request
:   @@snip [resolver-cross-project.sh](assets/resolvers/resolver-cross-project-put.sh)

Payload
:   @@snip [resolver-cross-project.json](assets/resolvers/resolver-cross-project.json)

Response
:   @@snip [resolver-cross-project-ref-new.json](assets/resolvers/resolver-cross-project-ref-new.json)

## Create a resolver using POST

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
:   @@snip [resolver-cross-project.sh](assets/resolvers/resolver-cross-project.sh)

Payload
:   @@snip [resolver-cross-project.json](assets/resolvers/resolver-cross-project.json)

Response
:   @@snip [resolver-cross-project-ref-new.json](assets/resolvers/resolver-cross-project-ref-new.json)


## Create a resolver using PUT
This alternative endpoint to create a resolver is useful in case the json payload does not contain an `@id` but you 
want to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/resolvers/{org_label}/{project_label}/{resolver_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{resolver_id}`, the request will fail.

**Example**

Request
:   @@snip [resolver-cross-project.sh](assets/resolvers/resolver-cross-project-put.sh)

Payload
:   @@snip [resolver-cross-project-put.json](assets/resolvers/resolver-cross-project-put.json)

Response
:   @@snip [resolver-cross-project-ref-new.json](assets/resolvers/resolver-cross-project-ref-new.json)


## Update a resolver

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
:   @@snip [resolver-cross-project-update.sh](assets/resolvers/resolver-cross-project-update.sh)

Payload
:   @@snip [resolver-cross-project.json](assets/resolvers/resolver-cross-project.json)

Response
:   @@snip [resolver-cross-project-ref-updated.json](assets/resolvers/resolver-cross-project-ref-updated.json)


## Tag a resolver

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
:   @@snip [resolver-tag.sh](assets/resolvers/resolver-tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [resolver-cross-project-ref-tagged.json](assets/resolvers/resolver-cross-project-ref-tagged.json)


## Deprecate a resolver

Locks the resolver, so no further operations can be performed. It will also not be taken into account in the resolution process.

Deprecating a resolver is considered to be an update as well. 

```
DELETE /v1/resolvers/{org_label}/{project_label}/{resolver_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resolver.

**Example**

Request
:   @@snip [resolver-deprecate.sh](assets/resolvers/resolver-deprecate.sh)

Response
:   @@snip [resolver-ref-deprecated.json](assets/resolvers/resolver-cross-project-ref-deprecated.json)


## Fetch a resolver

When fetching a resolver, the response format can be chosen through HTTP content negotiation, using the **Accept** HTTP header.

- **application/ld+json**: JSON-LD output response. Further specifying the query parameter `format=compacted|expanded` 
  will provide with the JSON-LD @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } 
  or the @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **application/n-triples**: RDF n-triples response, as defined by the @link:[w3](https://www.w3.org/TR/n-triples/){ open=new }.
- **text/vnd.graphviz**: A @link:[DOT response](https://www.graphviz.org/doc/info/lang.html){ open=new }.

If `Accept: */*` HTTP header is present, Nexus defaults to the JSON-LD output in compacted form.

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [resolver-fetch.sh](assets/resolvers/resolver-fetch.sh)

Response
:   @@snip [resolver-fetched.json](assets/resolvers/resolver-fetched.json)


## Fetch a resolver original payload

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}/source?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [resolver-fetch.sh](assets/resolvers/resolver-fetch-source.sh)

Response
:   @@snip [resolver-fetched.json](assets/resolvers/resolver-fetched-source.json)

## List resolvers

```
GET /v1/resolvers/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&q={search}&sort={sort}
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
  to enable sorting by multiple fields


**Example**

Request
:   @@snip [resolver-list.sh](assets/resolvers/resolver-list.sh)

Response
:   @@snip [resolver-list.json](assets/resolvers/resolver-list.json)


## Fetch resource using resolvers

Fetches a resource using the provided resolver. 

If the resolver segment (`{resolver_id}`) is `_` the resource is fetched from the first resolver in the requested 
project (`{org_label}/{project_label}`). The resolvers are ordered by its priority field.

```
GET /v1/resolvers/{org_label}/{project_label}/{resolver_id}/{resource_id}?rev={rev}&tag={tag}
```
... where

- `{resource_id}`: Iri - the @id value of the resource to be retrieved.
- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [resolver-fetch-resource.sh](assets/resolvers/resolver-fetch-resource.sh)

Response
:   @@snip [resource-fetched.json](assets/resources/resource-fetched.json)
