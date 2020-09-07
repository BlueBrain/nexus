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

Access to resources in the system depends on the access control list set for them. Depending on the access control list, 
a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn more about how 
to retrieve an access token.

@@@ note { .tip title="Authorization notes" }	

When modifying schemas, the caller must have `schemas/write` permissions on the current path of the project or the 
ancestor paths.

When reading schemas, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

@@@

## Create a schema using POST

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
:   @@snip [schema.sh](assets/schemas/schema.sh)

Payload
:   @@snip [schema.json](assets/schemas/schema.json)

Response
:   @@snip [schema-ref-new.json](assets/schemas/schema-ref-new.json)


## Create a schema using PUT

This alternative endpoint to create a schema is useful in case the json payload does not contain an `@id` but you want 
to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/schemas/{org_label}/{project_label}/{schema_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{schema_id}`, the request will fail.

**Example**

Request
:   @@snip [schema-put.sh](assets/schemas/schema-put.sh)

Payload
:   @@snip [schema.json](assets/schemas/schema.json)

Response
:   @@snip [schema-ref-new.json](assets/schemas/schema-ref-new.json)


## Update a schema

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
:   @@snip [schema-update.sh](assets/schemas/schema-update.sh)

Payload
:   @@snip [schema.json](assets/schemas/schema.json)

Response
:   @@snip [schema-ref-new-updated.json](assets/schemas/schema-ref-new-updated.json)


## Tag a schema

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
:   @@snip [schema-tag.sh](assets/schemas/schema-tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [schema-ref-new-tagged.json](assets/schemas/schema-ref-new-tagged.json)

## Deprecate a schema

Locks the schema, so no further operations can be performed. It also deletes the schema from listing/querying results.

Deprecating a schema is considered to be an update as well. 

```
DELETE /v1/schemas/{org_label}/{project_label}/{schema_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the schema.

**Example**

Request
:   @@snip [schema-deprecate.sh](assets/schemas/schema-deprecate.sh)

Response
:   @@snip [schema-ref-new-deprecated.json](assets/schemas/schema-ref-new-deprecated.json)

## Fetch a schema

When fetching a schema, the response format can be chosen through HTTP content negotiation, using the **Accept** HTTP 
header.

- **application/ld+json**: JSON-LD output response. Further specifying the query parameter `format=compacted|expanded` 
  will provide with the JSON-LD @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } or
  the @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **application/n-triples**: RDF n-triples response, as defined by the @link:[w3](https://www.w3.org/TR/n-triples/){ open=new }.
- **text/vnd.graphviz**: A @link:[DOT response](https://www.graphviz.org/doc/info/lang.html){ open=new }.

If `Accept: */*` HTTP header is present, Nexus defaults to the JSON-LD output in compacted form.

```
GET /v1/schemas/{org_label}/{project_label}/{schema_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [schema-fetch.sh](assets/schemas/schema-fetch.sh)

Response
:   @@snip [schema-fetched.json](assets/schemas/schema-fetched.json)

## Fetch a schema original payload

```
GET /v1/schemas/{org_label}/{project_label}/{schema_id}/source?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [schema-fetch.sh](assets/schemas/schema-fetch-source.sh)

Response
:   @@snip [resource-fetched.json](assets/schemas/schema-fetched-source.json)

## List schemas

```
GET /v1/schemas/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&q={search}&sort={sort}
```
                                            
where...

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
  to enable sorting by multiple fields


**Example**

Request
:   @@snip [schema-list.sh](assets/schemas/schema-list.sh)

Response
:   @@snip [schema-list.json](assets/schemas/schema-list.json)
