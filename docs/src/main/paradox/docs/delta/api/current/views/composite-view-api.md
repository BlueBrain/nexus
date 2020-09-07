# CompositeView

This view is composed by multiple `sources` and `projections`.

## Processing pipeline

An asynchronous process gets trigger for every view. This process can be visualized as a pipeline with different stages. 

The first stage is the input of the pipeline: a stream of sources.

The last stage takes the resulting output from the pipeline and index it on the configured projection.

[![CompositeView pipeline](../assets/views/compositeview_pipeline.png "CompositeView pipeline")](../assets/views/compositeview_pipeline.png)

## Sources

A source defines the location where to retrieve the resources. It is the input of the pipeline.

There are 3 types of sources available.

### ProjectEventStream

This source reads events in a streaming fashion from the current project event log.

The events are offered to the projections stage.

```json
{
   "sources": [
      {
         "@id": "{sourceId},
         "@type": "ProjectEventStream",
         "resourceSchemas": [ "{resourceSchema}", ...],
         "resourceTypes": [ "{resourceType}", ...],
         "resourceTag": "{tag}"
      }
   ],
   ...
}
```
where...

- `{sourceId}`: Iri - The identifier of the source. This field is optional. When missing, a randomly generated Iri will be assigned.
- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri. This field is optional.
- `{resourceType}`: Iri - Select only resources of the provided type Iri. This field is optional.
- `{tag}`: String - Selects only resources with the provided tag. This field is optional.

### CrossProjectEventStream

This source reads events in a streaming fashion from the defined project event log in the current Nexus deployment.

The specified list of identities will be used to retrieve the resources from the project. The target project must have `resources/read` permissions in order to read events.

The events are offered to the projections stage.

```json
{
   "sources": [
      {
         "@id": "{sourceId}",
         "@type": "CrossProjectEventStream",
         "project": "{project}",
         "identities": [ {_identity_}, {...} ],
         "resourceSchemas": [ "{resourceSchema}", ...],
         "resourceTypes": [ "{resourceType}", ...],
         "resourceTag": "{tag}"
      }
   ],
   ...
}
```
where...

- `{sourceId}`: Iri - The identifier of the source. This field is optional. When missing, a randomly generated Iri 
  will be assigned.
- `{project}`: String - the target project (in the format 'myorg/myproject').
- `_identity_`: Json object - the identity against which to enforce ACLs during the resource retrieval process.
- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri. This field is 
  optional.
- `{resourceType}`: Iri - Select only resources of the provided type Iri. This field is optional.
- `{tag}`: String - Selects only resources with the provided tag. This field is optional.

### RemoteProjectEventStream

This source reads events in a streaming fashion from the defined project event log in a remote Nexus deployment.

The events are offered to the projections stage.

```json
{
   "sources": [
      {
         "@id": "{sourceId}",
         "@type": "RemoteProjectEventStream",
         "project": "{project}",
         "endpoint": "{endpoint}",
         "token": "{token}",
         "resourceSchemas": [ "{resourceSchema}", ...],
         "resourceTypes": [ "{resourceType}", ...],
         "resourceTag": "{tag}"
      }
   ],
   ...
}
```

where...

- `{sourceId}`: Iri - The identifier of the source. This field is optional. When missing, a randomly generated Iri 
  will be assigned.
- `{project}`: String - the remote project (in the format 'myorg/myproject').
- `{endpoint}`: Iri - the Nexus deployment endpoint.
- `{token}`: String - the Nexus deployment token. This field is optional. When missing, the Nexus endpoint will be 
  accessed without authentication.
- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri. This field is 
  optional.
- `{resourceType}`: Iri - Select only resources of the provided type Iri. This field is optional.
- `{tag}`: String - Selects only resources with the provided tag. This field is optional.

## Intermediate Sparql space

After the events are gathered from each source, the following steps are executed:

1. Convert event into a resource.
2. Discard undesired resources.
3. Store the RDF triple representation of a resource in an intermediate Sparql space. This space will be used by the 
   projections in the following pipeline steps.

## Projections

A projection defines the type of indexing and a query to transform the data. It is the output of the pipeline.

There are 2 types of projections available

### ElasticSearchProjection

This projection executes the following steps:

1. Discard undesired resources.
2. Transform the resource by executing an SPARQL construct query against the intermediate Sparql space.
3. Convert the resulting RDF triples into JSON using the provided JSON-LD context.
4. Stores the resulting JSON as a Document in an ElasticSearch index.

```json
{
   "projections": [
      {
         "@id": "{projectionId}",
         "@type": "ElasticSearchProjection",
         "mapping": _elasticsearch mapping_,
         "query": "{query}",
         "context": _context_,
         "resourceSchemas": [ "{resourceSchema}", ...],
         "resourceTypes": [ "{resourceType}", ...],
         "resourceTag": "{tag}",
         "includeMetadata": {includeMetadata},
         "includeDeprecated": {includeDeprecated}
      }
   ],
   ...
}
```

where...

- `{projectionId}`: Iri - The identifier of the projection. This field is optional. When missing, a randomly generated 
  Iri will be assigned.
- `_elasticsearch mapping_`: Json object - Defines the value types for the Json keys, as stated at the 
  @link:[ElasticSearch mapping documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-put-mapping.html#indices-put-mapping){ open=new }.
- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri to perform the 
  query. This field is optional.
- `{resourceType}`: Iri - Select only resources of the provided type Iri to perform the query. This field is optional.
- `{tag}`: String - Selects only resources with the provided tag to perform the query. This field is optional.
- `{includeMetadata}`: Boolean - If true, the resource's nexus metadata (`_constrainedBy`, `_deprecated`, ...) will be 
  stored in the ElasticSearch document. Otherwise it won't. The default value is `false`.
- `{includeDeprecated}`: Boolean - If true, deprecated resources are also indexed. The default value is `false`.
- `{query}`: [Sparql Query](https://www.w3.org/TR/rdf-sparql-query/) - Defines the Sparql query to execute against the 
  intermediate Sparql space for each target resource.
- `_context_`: Json - the JSON-LD context value applied to the query results.

### SparqlProjection

This projection executes the following steps:

1. Discard undesired resources.
2. Transform the resource by executing an Sparql construct query against the intermediate Sparql space.
3. Stores the resulting RDF Triple in a Blazegraph namespace.

```json
{
   "projections": [
      {
         "@id": "{projectionId}",
         "@type": "SparqlProjection",
         "query": "{query}",
         "resourceSchemas": [ "{resourceSchema}", ...],
         "resourceTypes": [ "{resourceType}", ...],
         "resourceTag": "{tag}",
         "includeMetadata": {includeMetadata},
         "includeDeprecated": {includeDeprecated}
      }
   ],
   ...
}
```

where...

- `{projectionId}`: Iri - The identifier of the projection. This field is optional. When missing, a randomly generated 
  Iri will be assigned.
- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri to perform the 
  query. This field is optional.
- `{resourceType}`: Iri - Select only resources of the provided type Iri to perform the query. This field is optional.
- `{tag}`: String - Selects only resources with the provided tag to perform the query. This field is optional.
- `{includeMetadata}`: Boolean - If true, the resource's nexus metadata (`_constrainedBy`, `_deprecated`, ...) will be 
  stored in the ElasticSearch document. Otherwise it won't. The default value is `false`.
- `{includeDeprecated}`: Boolean - If true, deprecated resources are also indexed. The default value is `false`.
- `{query}`: @link:[Sparql Query](https://www.w3.org/TR/rdf-sparql-query/){ open=new } - Defines the Sparql query to 
  execute against the intermediate Sparql space for each target resource.


## Payload

```json
{
  "@id": "{someid}",
  "@type": ["CompositeView", "Beta"],
  "sources": [ _source_, ...],
  "projections": [ _projection_, ...],
  "rebuildStrategy": {
    "@type": "Interval",
    "value": "{interval_value}"
  }
}
```

where...

- `_source_`: Json - The source definition.
- `_projection_`: Json - The projection definition.
- `{interval_value}`: String - The maximum interval delay for a resource to be present in a projection, in a human 
  readable format (e.g.: 10 minutes). 

Note: The `rebuildStrategy` block is optional. If missing, the view won't be automatically restarted.

### Example

The following example creates a Composite view containing 3 sources and 2 projections.

The incoming data from each of the sources is stored as RDF triples in the intermediate Sparql space . 

The ElasticSearch projection `http://music.com/bands` is only going to query the Sparql space with the provided query 
when the current resource in the pipeline has the type `http://music.com/Band`.

The ElasticSearch projection `http://music.com/albums` is only going to query the Sparql space with the provided query 
when the current resource in the pipeline has the type `http://music.com/Album`.

The view is going to be restarted every 10 minutes if there are new resources in any of the sources since the last time 
the view was restarted. This allows to deal with partial graph visibility issues.

```json
{
  "@type": ["CompositeView", "Beta"],
  "sources": [
    {
      "@id": "http://music.com/sources/local",
      "@type": "ProjectEventStream"
    },
    {
      "@id": "http://music.com/sources/albums",
      "@type": "CrossProjectEventStream",
      "project": "demo/albums",
      "identities": {
          "realm": "myrealm",
          "group": "mygroup"
      }
    },
    {
      "@id": "http://music.com/sources/songs",
      "@type": "RemoteProjectEventStream",
      "project": "remote_demo/songs",
      "endpoint": "https://example2.nexus.com",
      "token": "mytoken"
    }    
  ],
  "projections": [
    {
      "@id": "http://music.com/bands",
      "@type": "ElasticSearchProjection",
      "mapping": {
        "properties": {
          "@type": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "@id": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "name": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "genre": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "album": {
            "type": "nested",
            "properties": {
              "title": {
                "type": "keyword",
                "copy_to": "_all_fields"
              },
              "released": {
                "type": "date",
                "copy_to": "_all_fields"
              },
              "song": {
                "type": "nested",
                "properties": {
                  "title": {
                    "type": "keyword",
                    "copy_to": "_all_fields"
                  },
                  "number": {
                    "type": "long",
                    "copy_to": "_all_fields"
                  },
                  "length": {
                    "type": "long",
                    "copy_to": "_all_fields"
                  }
                }
              }
            }
          },
          "_all_fields": {
            "type": "text"
          }
        },
        "dynamic": false
      },
      "query": "prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT {{resource_id}   music:name       ?bandName ; music:genre      ?bandGenre ; music:album      ?albumId . ?albumId        music:released   ?albumReleaseDate ; music:song       ?songId . ?songId         music:title      ?songTitle ; music:number     ?songNumber ; music:length     ?songLength } WHERE {{resource_id}   music:name       ?bandName ; music:genre      ?bandGenre . OPTIONAL {{resource_id} ^music:by        ?albumId . ?albumId        music:released   ?albumReleaseDate . OPTIONAL {?albumId         ^music:on        ?songId . ?songId          music:title      ?songTitle ; music:number     ?songNumber ; music:length     ?songLength } } } ORDER BY(?songNumber)",
      "context": {
        "@base": "http://music.com/",
        "@vocab": "http://music.com/"
      },
      "resourceTypes": [
        "http://music.com/Band"
      ]
    },
    {
      "@id": "http://music.com/albums",
      "@type": "ElasticSearchProjection",
      "mapping": {
        "properties": {
          "@type": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "@id": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "name": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "length": {
            "type": "long",
            "copy_to": "_all_fields"
          },
          "numberOfSongs": {
            "type": "long",
            "copy_to": "_all_fields"
          },
          "_all_fields": {
            "type": "text"
          }
        },
        "dynamic": false
      },
      "query": "prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT {{resource_id}             music:name               ?albumTitle ; music:length             ?albumLength ; music:numberOfSongs      ?numberOfSongs } WHERE {SELECT ?albumReleaseDate ?albumTitle (sum(?songLength) as ?albumLength) (count(?albumReleaseDate) as ?numberOfSongs) WHERE {OPTIONAL { {resource_id}           ^music:on / music:length   ?songLength } {resource_id} music:released             ?albumReleaseDate ; music:title                ?albumTitle . } GROUP BY ?albumReleaseDate ?albumTitle }",
      "context": {
        "@base": "http://music.com/",
        "@vocab": "http://music.com/"
      },
      "resourceTypes": [
        "http://music.com/Album"
      ]
    }
  ],
  "rebuildStrategy": {
    "@type": "Interval",
    "value": "10 minutes"
  }  
}
```

## Endpoints

The following sections describe the endpoints that are specific to a CompositeView.

The general view endpoints are described on the @ref:[parent page](index.md#endpoints).

### Search Documents in a projection

```
POST /v1/views/{org_label}/{project_label}/{view_id}/projections/{projection_id}/_search
  {...}
```

where `{projection_id}` is the @id value of the target `ElasticSearch` projection. 

The supported payload is defined on the 
@link:[ElasticSearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html){ open=new }


**Example**

Request
:   @@snip [composite-view-es-id-search.sh](../assets/views/composite-view-es-id-search.sh)

Payload
:   @@snip [composite-view-es-payload.json](../assets/views/composite-view-es-search-payload.json)

Response
:   @@snip [composite-view-es-search.json](../assets/views/composite-view-es-search.json)

### Search Documents in all projections

```
POST /v1/views/{org_label}/{project_label}/{view_id}/projections/_/_search
  {...}
```

The supported payload is defined on the 
@link:[ElasticSearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html){ open=new }


**Example**

Request
:   @@snip [composite-view-es-search.sh](../assets/views/composite-view-es-search.sh)

Payload
:   @@snip [composite-view-es-payload.json](../assets/views/composite-view-es-search-payload.json)

Response
:   @@snip [composite-view-es-search.json](../assets/views/composite-view-es-search.json)


### SPARQL query in a projection

```
POST /v1/views/{org_label}/{project_label}/{view_id}/projections/{projection_id}/sparql
  {query}
```

```
GET /v1/views/{org_label}/{project_label}/{view_id}/projections/{projection_id}/sparql?query={query}
```

In both endpoints, `{query}` is defined by the 
@link:[SPARQL documentation](https://www.w3.org/TR/rdf-sparql-query/#basicpatterns){ open=new }

where `{projection_id}` is the @id value of the target `Sparql` projection.

The `Content-Type` HTTP header for POST request is `application/sparql-query`.


**Example**

Request
:   @@snip [composite-view-sparql-search.sh](../assets/views/composite-view-sparql-id-search.sh)

Response
:   @@snip [composite-view-sparql-search.json](../assets/views/composite-view-sparql-search.json)

### SPARQL query in all projections

```
POST /v1/views/{org_label}/{project_label}/{view_id}/projections/_/sparql
  {query}
```

```
GET /v1/views/{org_label}/{project_label}/{view_id}/projections/_/sparql?query={query}
```

In both endpoints, `{query}` is defined by the 
@link:[SPARQL documentation](https://www.w3.org/TR/rdf-sparql-query/#basicpatterns){ open=new }

The `Content-Type` HTTP header for POST request is `application/sparql-query`.


**Example**

Request
:   @@snip [composite-view-sparql-projections-search.sh](../assets/views/composite-view-sparql-projections-search.sh)

Response
:   @@snip [composite-view-sparql-search.json](../assets/views/composite-view-sparql-search.json)

### SPARQL query in the intermediate space

```
POST /v1/views/{org_label}/{project_label}/{view_id}/sparql
  {query}
```

```
GET /v1/views/{org_label}/{project_label}/{view_id}/sparql?query={query}
```

In both endpoints, `{query}` is defined by the 
@link:[SPARQL documentation](https://www.w3.org/TR/rdf-sparql-query/#basicpatterns){ open=new }

The `Content-Type` HTTP header for POST request is `application/sparql-query`.


**Example**

Request
:   @@snip [composite-view-intermediate-sparql-search.sh](../assets/views/composite-view-intermediate-sparql-search.sh)

Response
:   @@snip [composite-view-sparql-search.json](../assets/views/composite-view-sparql-search.json)


### Fetch statistics

This endpoint displays statistical information about the intermediate Sparql space.

```
GET /v1/views/{org_label}/{project_label}/{view_id}/statistics
```

**Example**

Request
:   @@snip [compositeview-fetch-intermediate-stats.sh](../assets/views/compositeview-fetch-intermediate-stats.sh)

Response
:   @@snip [compositeview-fetch-stats.json](../assets/views/compositeview-fetch-stats.json)

where:

- `totalEvents` - sum of total number of events from each source
- `processedEvents` - sum of number of events that have been considered by each source
- `remainingEvents` - sum of number of events that remain to be considered by each source
- `discardedEvents` - sum of number of events that have been discarded by each source (were not evaluated due to 
  filters, e.g. did not match schema, tag or type defined in the source)
- `evaluatedEvents` - sum of number of events that have been used to update the intermediate Sparql space of each source
- `lastEventDateTime` - timestamp of the last event in the sources
- `lastProcessedEventDateTime` - timestamp of the last event processed by the sources
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp
- `sourceId` - the @id unique value of the source

 
### Fetch source statistics

This endpoint displays statistical information about the provided source.

```
GET /v1/views/{org_label}/{project_label}/{view_id}/sources/{source_id}/statistics
```

where `{source_id}` is the @id value of the source.

**Example**

Request
:   @@snip [compositeview-fetch-source-id-stats.sh](../assets/views/compositeview-fetch-source-id-stats.sh)

Response
:   @@snip [compositeview-fetch-source-stat.json](../assets/views/compositeview-fetch-source-stat.json)

where:

- `totalEvents` - total number of events for the provided source
- `processedEvents` - number of events that have been considered by the provided source
- `remainingEvents` - number of events that remain to be considered by the provided source
- `discardedEvents` - number of events that have been discarded by the provided source (were not evaluated due to 
  filters, e.g. did not match schema, tag or type defined in the source)
- `evaluatedEvents` - number of events that have been used to update the intermediate Sparql of the provided source
- `lastEventDateTime` - timestamp of the last event from the provided source
- `lastProcessedEventDateTime` - timestamp of the last event processed by the provided source
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp

### Fetch all sources statistics

This endpoint displays statistical information about all the sources.

```
GET /v1/views/{org_label}/{project_label}/{view_id}/sources/_/statistics
```

**Example**

Request
:   @@snip [compositeview-fetch-sources-stats.sh](../assets/views/compositeview-fetch-sources-stats.sh)

Response
:   @@snip [compositeview-fetch-sources-stat.json](../assets/views/compositeview-fetch-sources-stat.json)

where:

- `sourceId` - the @id unique value of the source
- `totalEvents` - total number of events for the provided source
- `processedEvents` - number of events that have been considered by the provided source
- `remainingEvents` - number of events that remain to be considered by the provided source
- `discardedEvents` - number of events that have been discarded by the provided source (were not evaluated due to 
  filters, e.g. did not match schema, tag or type defined in the source)
- `evaluatedEvents` - number of events that have been used to update the intermediate Sparql of the provided source
- `lastEventDateTime` - timestamp of the last event from the provided source
- `lastProcessedEventDateTime` - timestamp of the last event processed by the provided source
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp
 
 
### Fetch projection statistics

This endpoint displays statistical information about the provided projection.
 
```
GET /v1/views/{org_label}/{project_label}/{view_id}/projections/{projection_id}/statistics
```

where `{projection_id}` is the @id value of the projection.


**Example**

Request
:   @@snip [compositeview-fetch-stats.sh](../assets/views/compositeview-fetch-id-stats.sh)

Response
:   @@snip [compositeview-fetch-stats.json](../assets/views/compositeview-fetch-stats.json)

where:

- `sourceId` - the @id unique value of the source
- `projectionId` - the @id unique value of the projection
- `totalEvents` - total number of events for the provided source
- `processedEvents` - number of events that have been considered by the projection
- `remainingEvents` - number of events that remain to be considered by the projection
- `discardedEvents` - number of events that have been discarded (were not evaluated due to filters, e.g. did not match 
  schema, tag or type defined in the projection)
- `evaluatedEvents` - number of events that have been used to update the projection index
- `lastEventDateTime` - timestamp of the last event in the source
- `lastProcessedEventDateTime` - timestamp of the last event processed by the projection
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp

### Fetch all projections statistics
 
This endpoint displays statistical information about the all projections.

```
GET /v1/views/{org_label}/{project_label}/{view_id}/projections/_/statistics
```

**Example**

Request
:   @@snip [compositeview-fetch-stats.sh](../assets/views/compositeview-fetch-stats.sh)

Response
:   @@snip [compositeview-fetch-all-stats.json](../assets/views/compositeview-fetch-all-stats.json)

where:

- `sourceId` - the @id unique value of the source
- `projectionId` - the @id unique value of the projection
- `totalEvents` - total number of events for the provided source
- `processedEvents` - number of events that have been considered by the projection
- `remainingEvents` - number of events that remain to be considered by the projection
- `discardedEvents` - number of events that have been discarded (were not evaluated due to filters, e.g. did not match 
  schema, tag or type defined in the projection)
- `evaluatedEvents` - number of events that have been used to update the projection index
- `lastEventDateTime` - timestamp of the last event in the source
- `lastProcessedEventDateTime` - timestamp of the last event processed by the projection
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp

### Restart view

This endpoint restarts the view indexing process. It does not delete the created indices/namespaces but it overrides 
the graphs/documents when going through the event log.
 
```
DELETE /v1/views/{org_label}/{project_label}/{view_id}/offset
```

**Example**

Request
:   @@snip [view-restart.sh](../assets/views/view-restart.sh)

Response
:   @@snip [composite-view-restart.json](../assets/views/composite-view-restart.json)


### Restart projection

This endpoint restarts indexing process for the provided projection while keeping the sources (and the intermediate 
Sparql space) progress.
 
```
DELETE /v1/views/{org_label}/{project_label}/{view_id}/projections/{projection_id}/offset
```


where `{projection_id}` is the @id value of the projection.

**Example**

Request
:   @@snip [composite-view-projection-id-restart.sh](../assets/views/composite-view-projection-id-restart.sh)

Response
:   @@snip [composite-view-projection-id-restart.json](../assets/views/composite-view-projection-id-restart.json)

### Restart all projections

This endpoint restarts indexing process for all projections while keeping the sources (and the intermediate Sparql 
space) progress.
 
```
DELETE /v1/views/{org_label}/{project_label}/{view_id}/projections/_/offset
```

**Example**

Request
:   @@snip [composite-view-projection-restart.sh](../assets/views/composite-view-projection-restart.sh)

Response
:   @@snip [composite-view-projection-restart.json](../assets/views/composite-view-projection-restart.json)
