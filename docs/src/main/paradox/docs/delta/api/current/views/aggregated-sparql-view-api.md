# AggregateSparqlView

This view is an aggregate of SparqlViews. The view itself does not create any namespace, but it references the already 
existing namespaces of the linked SparqlViews.

When performing queries on the `sparql` endpoint, this view will query all the underlying SparqlViews and then aggregate 
the results. The order how the results across the different SparqlView gets merged it is not deterministic.

If the caller does not have the permission `views/query` (or from v1.5, the user-defined permission) on all the views defined on the aggregated view, only a 
subset of namespaces (or none) will be selected, respecting the defined permissions.

## Payload

```json
{
  "@id": "{someid}",
  "@type": "AggregateSparqlView",
  "views": [
    {
        "project": "{project}",
        "viewId": "{viewId}"
    },
    ...
  ]
}
```

where...
 
- `{project}`: String - the project, defined as `{org_label}/{project_label}`, where the `{viewId}` is located.
- `{viewId}`: Iri - The Blazegraph view @id value to be aggregated.

@@@ note

This approach to aggregate data from SPARQL does not circumvent the fact that each namespace is isolated. Neither it 
deals with sorting or filtering in an aggregated manner.

For that reason, path traversals will not work out of the scope of the single namespace (even using an aggregate view).

Ordering and DISTINCT selection won't work either, due to the fact that the query is executed on each namespace 
independently.

In order to have a more robust SPARQL aggregation support, please make us of CompositeView.

@@@


## Create using POST

```
POST /v1/view/{org_label}/{project_label}
  {...}
```

The json payload:

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is
  the `prefix` defined on the view's project (`{project_label}`).

**Example**

Request
:   @@snip [create.sh](../assets/views/blazegraph/aggregate/create.sh)

Payload
:   @@snip [payload.json](../assets/views/blazegraph/aggregate/payload.json)

Response
:   @@snip [created.json](../assets/views/blazegraph/aggregate/created.json)

## Create using PUT

This alternative endpoint to create a view is useful in case the json payload does not contain an `@id` but you want
to specify one. The @id will be specified in the last segment of the endpoint URI.
```
PUT /v1/views/{org_label}/{project_label}/{view_id}
  {...}
```

**Example**

Note that if the payload contains an @id different from the `{view_id}`, the request will fail.

Request
:   @@snip [create.sh](../assets/views/blazegraph/aggregate/create-put.sh)

Payload
:   @@snip [payload.json](../assets/views/blazegraph/aggregate/payload.json)

Response
:   @@snip [created.json](../assets/views/blazegraph/aggregate/created.json)

## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a view without having had seen the previous revision of
the view, the last revision needs to be passed as a query parameter.

```
PUT /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
  {...}
```
... where `{previous_rev}` is the last known revision number for the view.

**Example**

Request
:   @@snip [update.sh](../assets/views/blazegraph/aggregate/update.sh)

Payload
:   @@snip [payload.json](../assets/views/blazegraph/aggregate/payload.json)

Response
:   @@snip [updated.json](../assets/views/blazegraph/aggregate/updated.json)

## Tag

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
:   @@snip [tag.sh](../assets/views/tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [tagged.json](../assets/views/blazegraph/aggregate/tagged.json)


## Deprecate

Locks the view, so no further operations can be performed. It also stops the indexing process and delete the associated namespace.

Deprecating a view is considered to be an update as well.

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the view.

**Example**

Request
:   @@snip [deprecate.sh](../assets/views/deprecate.sh)

Response
:   @@snip [deprecated.json](../assets/views/blazegraph/aggregate/deprecated.json)


## Fetch

```
GET /v1/views/{org_label}/{project_label}/{view_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
  `{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch.sh](../assets/views/fetch.sh)

Response
:   @@snip [fetched.json](../assets/views/blazegraph/aggregate/fetched.json)

## Fetch original payload

```
GET /v1/views/{org_label}/{project_label}/{view_id}/source?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
  `{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchSource.sh](../assets/views/fetchSource.sh)

Response
:   @@snip [payload.json](../assets/views/blazegraph/aggregate/payload.json)


## SPARQL query

Provides aggregated search functionality across all the `SparqlView`s referenced from the target `view_id`.

```
POST /v1/views/{org_label}/{project_label}/{view_id}/sparql
  {query}
```
or
```
GET /v1/views/{org_label}/{project_label}/{view_id}/sparql?query={query}
```

In both endpoints, `{query}` is defined by the 
@link:[SPARQL documentation](https://www.w3.org/TR/rdf-sparql-query/#basicpatterns){ open=new }

The `Content-Type` HTTP header for POST request is `application/sparql-query`.

From Delta 1.5, we have added support for multiple Content Negotiation types when querying the SPARQL view, allowing clients to request different response formats. The Content Negotiation is controlled by the HTTP `Accept` header. The following values are supported:

- **application/ld+json**: Returns an expanded JSON-LD document. This is supported for a subset of SPARQL queries.
- **application/n-triples**: Returns the n-triples representation. This is supported for a subset of SPARQL queries
- **application/rdf+xml**: Returns an XML document.
- **application/sparql-results+xml**: Returns the sparql results in XML.
- **application/sparql-results+json**: Returns the sparql results in Json (default).

**Example**

Request
:   @@snip [search.sh](../assets/views/blazegraph/search.sh)

Response
:   @@snip [search.json](../assets/views/blazegraph/search.json)

