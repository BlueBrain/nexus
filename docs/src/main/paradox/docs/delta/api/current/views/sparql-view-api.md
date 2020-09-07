# SparqlView

This view creates a Blazegraph `namespace` and stores the targeted resources as RDF triples into an Sparql store.

The triples created on each view are isolated from triples created on another view through the namespace.

A default view gets automatically created when the project is created but other views can be created.

## Processing pipeline

An asynchronous process gets trigger for every view. This process can be visualized as a pipeline with different stages. 

The first stage is the input of the pipeline: a stream of events scoped for the project where the view was created.

The last stage takes the resource, generated through the pipeline steps, and extracts its RDF triples to store them in a Blazegraph namespace.

[![SparqlView pipeline](../assets/views/sparql_pipeline.png "SparqlView pipeline")](../assets/views/sparql_pipeline.png)


## Payload

```json
{
  "@id": "{someid}",
  "@type": "SparqlView",
  "resourceSchemas": [ "{resourceSchema}", ...],
  "resourceTypes": [ "{resourceType}", ...],
  "resourceTag": "{tag}",
  "includeMetadata": {includeMetadata},
  "includeDeprecated": {includeDeprecated}
}
```

where...

- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri. This field is optional.
- `{resourceType}`: Iri - Selects only resources of the provided type Iri. This field is optional.
- `{tag}`: String - Selects the resources with the provided tag. This field is optional.
- `{includeMetadata}`: Boolean - If true, the resource's nexus metadata (`_constrainedBy`, `_deprecated`, ...) will be stored in the Sparql graph. Otherwise it won't. The default value is `false`.
- `{includeDeprecated}`: Boolean - If true, deprecated resources are also indexed. The default value is `false`.


### Example

The following example creates an Sparql view that will index resources not deprecated and with tag `mytag`.

The resulting RDF triples will contain the resources metadata.

```json
{
  "@id": "https://bluebrain.github.io/nexus/vocabulary/myview",
  "@type": [
    "SparqlView"
  ],
  "includeMetadata": true,
  "includeDeprecated": false,
  "resourceTag": "mytag"
}

```

## Endpoints

The following sections describe the endpoints that are specific to an SparqlView.

The general view endpoints are described on the @ref[parent page](index.md#endpoints).

### SPARQL query

Provides search functionality on the `SparqlView` content.

```
POST /v1/views/{org_label}/{project_label}/{view_id}/sparql
  {query}
```
or
```
GET /v1/views/{org_label}/{project_label}/{view_id}/sparql?query={query}
```

In both endpoints, `{query}` is defined by the @link:[SPARQL documentation](https://www.w3.org/TR/rdf-sparql-query/#basicpatterns){ open=new }

The `Content-Type` HTTP header for POST request is `application/sparql-query`.

**Example**

Request
:   @@snip [sparql-view-search.sh](../assets/views/sparql-view-search.sh)

Response
:   @@snip [sparql-view-search.json](../assets/views/sparql-view-search.json)


### Fetch statistics

```
GET /v1/views/{org_label}/{project_label}/{view_id}/statistics
```

**Example**

Request
:   @@snip [view-fetch.sh](../assets/views/view-statistics.sh)

Response
:   @@snip [view-fetched.json](../assets/views/view-statistics.json)

where...

- `totalEvents` - total number of events in the project
- `processedEvents` - number of events that have been considered by the view
- `remainingEvents` - number of events that remain to be considered by the view
- `discardedEvents` - number of events that have been discarded (were not evaluated due to filters, e.g. did not match schema, tag or type defined in the view)
- `evaluatedEvents` - number of events that have been used to update an index
- `lastEventDateTime` - timestamp of the last event in the project
- `lastProcessedEventDateTime` - timestamp of the last event processed by the view
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp
 
### Restart view

This endpoint restarts the view indexing process. It does not delete the created namespaces but it overrides the resource GRAPH when going through the event log.

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}/offset
```

**Example**

Request
:   @@snip [view-restart.sh](../assets/views/view-restart.sh)

Response
:   @@snip [view-restart.json](../assets/views/view-restart.json)