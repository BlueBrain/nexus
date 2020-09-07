@@@ index

* @ref:[Elastic search view](elasticsearch-view-api.md)
* @ref:[Sparql view](sparql-view-api.md)
* @ref:[Composite view](composite-view-api.md)
* @ref:[Aggregated Elastic search view](aggregated-es-view-api.md)
* @ref:[Aggregated Sparql view](aggregated-sparql-view-api.md)

@@@

# Views

Views are rooted in the `/v1/views/{org_label}/{project_label}` collection and are used to index the selected resources 
into a bucket. 

Each view... 

- belongs to a `project` identifier by the label `{project_label}` 
- inside an `organization` identifier by the label `{org_label}` 
- it is validated against the @link:[view schema](https://bluebrainnexus.io/schemas/view.json){ open=new }.

Access to resources in the system depends on the access control list set for them. Depending on the access control list, 
a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](../authentication.md) to learn more about 
how to retrieve an access token.

@@@ note { .tip title="Authorization notes" }	

When modifying views, the caller must have `views/write` permissions on the current path of the project or the ancestor paths.

When querying views, the caller must have `views/query` permissions on the current path of the project or the ancestor paths.

When reading views, the caller must have `resources/read` permissions on the current path of the project or the ancestor paths.

@@@

## View types

[![view types](../assets/views/view-defaults.png "View types")](../assets/views/view-defaults.png)



There are several types of views, which relies on different technology to perform the indexing

### SparqlView

A view that creates a Sparql namespace. which converts the targeted Json resources intro RDF triples and stores them in 
a Sparql store.

The triples created on each view are isolated from triples created on another view.

@ref:[More information](sparql-view-api.md)

### ElasticSearchView

A view which stores the targeted Json resources into an ElasticSearch Document.

The Documents created on each view are isolated from Documents created on other views by using different ElasticSearch 
indices.

@ref:[More information](elasticsearch-view-api.md)

### CompositeView

A view which is composed by multiple `sources` and `projections`.

A source defines from where to retrieve the resources. It is the input for the indexing in a later stage.

A projection defines the type of indexing and the transformations to apply to the data.

Composite views are useful when aggregating data across multiple projects (local or remote) using multiple sources. 
Afterwards, by defining multiple projections, the data can be adapted to the client needs.

@ref:[More information](composite-view-api.md)

### AggregateElasticSearchView

This view describes an aggregation of multiple existing ElasticSearch views. This approach is useful for searching 
documents across multiple ElasticSearch views.

When querying an AggregateElasticSearchView, the query is performed on each of the described views and the results 
are aggregated by ElasticSearch.

@ref:[More information](aggregated-es-view-api.md)

### AggregateSparqlView

This view describes an aggregation of multiple existing Sparql views. This approach is useful for searching triples 
across multiple Sparql views.

When querying an AggregateSparqlView, the query is performed on each of the described views. The Sparql store does 
not have means for aggregating the query and for that reason this approach is very limited.

@ref:[More information](aggregated-sparql-view-api.md)

## Endpoints

In the following sections we describe the endpoints that apply for every view subtype.

Some views have other endpoints to deal with specific functionality. Please refer to each view for further information. 

### Create a view using POST

```
POST /v1/view/{org_label}/{project_label}
  {...}
```

The json payload: 

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is 
the `prefix` defined on the view's project (`{project_label}`).


### Create a view using PUT

This alternative endpoint to create a view is useful in case the json payload does not contain an `@id` but you want 
to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/views/{org_label}/{project_label}/{view_id}
  {...}
```
 
Note that if the payload contains an @id different from the `{view_id}`, the request will fail.

### Update a View

This operation overrides the payload.

In order to ensure a client does not perform any changes to a view without having had seen the previous revision of
the view, the last revision needs to be passed as a query parameter.

```
PUT /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
  {...}
```
... where `{previous_rev}` is the last known revision number for the view.

### Tag a View

Links a view's revision to a specific name. 

Tagging a view is considered to be an update as well.

```
POST /v1/views/{org_label}/{project_label}/{view_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{previous_rev}`: Number - the last known revision for the resolver.
- `{name}`: String - label given to the view at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [view-tag.sh](../assets/views/view-tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [view-elastic-ref-tagged.json](../assets/views/view-elastic-ref-tagged.json)

### Deprecate a view

Locks the view, so no further operations can be performed. It also stops indexing any more resources into it.

Deprecating a view is considered to be an update as well. 

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the view.

**Example**

Request
:   @@snip [view-deprecate.sh](../assets/views/view-deprecate.sh)

Response
:   @@snip [view-elastic-ref-deprecated.json](../assets/views/view-elastic-ref-deprecated.json)


### Fetch a view

When fetching a view, the response format can be chosen through HTTP content negotiation, using the **Accept** HTTP header.

- **application/ld+json**: JSON-LD output response. Further specifying the query parameter `format=compacted|expanded` 
will provide with the JSON-LD @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } 
or the @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **application/n-triples**: RDF n-triples response, as defined by the @link:[w3](https://www.w3.org/TR/n-triples/){ open=new }.
- **text/vnd.graphviz**: A @link:[DOT response](https://www.graphviz.org/doc/info/lang.html){ open=new }.

If `Accept: */*` HTTP header is present, Nexus defaults to the JSON-LD output in compacted form.

```
GET /v1/views/{org_label}/{project_label}/{view_id}?rev={rev}&tag={tag}
```

where ...
- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [view-fetch.sh](../assets/views/view-fetch.sh)

Response
:   @@snip [view-fetched.json](../assets/views/view-fetched.json)

### Fetch a view original payload

```
GET /v1/views/{org_label}/{project_label}/{view_id}/source?rev={rev}&tag={tag}
```
where ...
- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [view-fetch.sh](../assets/views/view-fetch-source.sh)

Response
:   @@snip [view-fetched.json](../assets/views/view-fetched-source.json)


### List views

```
GET /v1/views/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&q={search}&sort={sort}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting views based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting views based on their revision value
- `{type}`: Iri - can be used to filter the resulting views based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value
- `{createdBy}`: Iri - can be used to filter the resulting views based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting views based on the person which performed the last update
- `{search}`: String - can be provided to select only the views in the collection that have attribute values matching 
  (containing) the provided string
- `{sort}`: String - can be used to sort views based on a payloads' field. This parameter can appear multiple times to 
  enable sorting by multiple fields


**Example**

Request
:   @@snip [view-list.sh](../assets/views/view-list.sh)

Response
:   @@snip [view-list.json](../assets/views/view-list.json)