# AggregateSparqlView

This view is an aggregate of SparqlViews. The view itself does not create any index, but it references the already 
existing indices of the linked SparqlViews.

When performing queries on the `sparql` endpoint, this view will query all the underlying SparqlViews and then aggregate 
the results. The order how the results across the different SparqlView gets merged it is not deterministic.

If the caller does not have the permission `views/query` on all the projects defined on the aggregated view, only a 
subset of indices (or none) will be selected, respecting the defined permissions.

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
- `{viewId}`: Iri - The view @id value to be aggregated.

@@@ note

This approach to aggregate data from SPARQL does not circumvent the fact that each namespace is isolated. Neither it 
deals with sorting or filtering in an aggregated manner.

For that reason, path traversals will not work out of the scope of the single namespace (even using an aggregate view).

Ordering and DISTINCT selection won't work either, due to the fact that the query is executed on each namespace 
independently.

In order to have a more robust SPARQL aggregation support, please make us of CompositeView.

@@@


## Endpoints

The following sections describe the endpoints that are specific to an AggregateSparqlView.

The general view endpoints are described on the @ref:[parent page](index.md#endpoints).

### SPARQL query

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

**Example**

Request
:   @@snip [sparql-view-search.sh](../assets/views/sparql-view-search.sh)

Response
:   @@snip [sparql-view-search.json](../assets/views/sparql-view-search.json)

