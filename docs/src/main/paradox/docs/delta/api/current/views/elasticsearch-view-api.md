# ElasticSearchView

This view creates an ElasticSearch `index` and stores the targeted Json resources into an ElasticSearch Document.

The documents created on each view are isolated from documents created on other views by using different ElasticSearch indices.

A default view gets automatically created when the project is created but other views can be created.

## Processing pipeline

An asynchronous process gets trigger for every view. This process can be visualized as a pipeline with different stages. 

The first stage is the input of the pipeline: a stream of events scoped for the project where the view was created.

The last stage takes the JSON document, generated through the pipeline steps, and stores it as a Document in an ElasticSearch index

[![ElasticSearchView pipeline](../assets/views/elasticsearch_pipeline.png "ElasticSearchView pipeline")](../assets/views/elasticsearch_pipeline.png)

## Payload

```json
{
  "@id": "{someid}",
  "@type": "ElasticSearchView",
  "resourceSchemas": [ "{resourceSchema}", ...],
  "resourceTypes": [ "{resourceType}", ...],
  "resourceTag": "{tag}",
  "sourceAsText": {sourceAsText},
  "includeMetadata": {includeMetadata},
  "includeDeprecated": {includeDeprecated},
  "mapping": _elasticsearch mapping_
}
```

where...

- `{resourceSchema}`: Iri - Selects only resources that are validated against the provided schema Iri. This field is optional.
- `{resourceType}`: Iri - Select only resources of the provided type Iri. This field is optional.
- `{tag}`: String - Selects only resources with the provided tag. This field is optional.
- `_elasticsearch mapping_`: Json object - Defines the value types for the Json keys, as stated at the 
  @link:[ElasticSearch mapping documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-put-mapping.html#indices-put-mapping){ open=new }.
- `{sourceAsText}`: Boolean - If true, the resource's payload will be stored in the ElasticSearch document as a single 
  escaped string value of the key `_original_source`. If false, the resource's payload will be stored normally in the ElasticSearch document. The default value is `false`.
- `{includeMetadata}`: Boolean - If true, the resource's nexus metadata (`_constrainedBy`, `_deprecated`, ...) will be 
  stored in the ElasticSearch document. Otherwise it won't. The default value is `false`.
- `{includeDeprecated}`: Boolean - If true, deprecated resources are also indexed. The default value is `false`.
- `{someid}`: Iri - The @id value for this view.

### Example

The following example creates an ElasticSearch view that will index resources validated against the schema with id 
`https://bluebrain.github.io/nexus/schemas/myschema`. If a resource is deprecated, it won't be selected for indexing.

The resulting ElasticSearch Documents fields will be indexed according to the provided mapping rules and they won't 
include the resource metadata fields.

```json
{
  "@id": "https://bluebrain.github.io/nexus/vocabulary/myview",
  "@type": [
    "ElasticSearchView"
  ],
  "mapping": {
    "dynamic": false,
    "properties": {
      "@id": {
        "type": "keyword"
      },
      "@type": {
        "type": "keyword"
      },
      "name": {
        "type": "keyword"
      },
      "number": {
        "type": "long"
      },
      "bool": {
        "type": "boolean"
      }
    }
  },
  "includeMetadata": false,
  "includeDeprecated": false,
  "sourceAsText": false,
  "resourceSchemas": [
    "https://bluebrain.github.io/nexus/schemas/myschema"
  ],
  "resourceTypes": []
}
```

## Endpoints

The following sections describe the endpoints that are specific to an ElasticSearchView.

The general view endpoints are described on the @ref:[parent page](index.md#endpoints).

### Search Documents

```
POST /v1/views/{org_label}/{project_label}/{view_id}/_search
  {...}
```
The supported payload is defined on the 
@link:[ElasticSearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html){ open=new }

The string `documents` is used as a prefix of the default ElasticSearch `view_id`

**Example**

Request
:   @@snip [elastic-view-search.sh](../assets/views/elastic-view-search.sh)

Payload
:   @@snip [elastic-view-payload.json](../assets/views/elastic-view-search-payload.json)

Response
:   @@snip [elastic-view-search.json](../assets/views/elastic-view-search.json)


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
- `discardedEvents` - number of events that have been discarded (were not evaluated due to filters, e.g. did not 
  match schema, tag or type defined in the view)
- `evaluatedEvents` - number of events that have been used to update an index
- `lastEventDateTime` - timestamp of the last event in the project
- `lastProcessedEventDateTime` - timestamp of the last event processed by the view
- `delayInSeconds` - number of seconds between the last processed event timestamp and the last known event timestamp


### Restart view

This endpoint restarts the view indexing process. It does not delete the created indices but it overrides the 
resource Document when going through the event log.

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}/offset
```

**Example**

Request
:   @@snip [view-restart.sh](../assets/views/view-restart.sh)

Response
:   @@snip [view-restart.json](../assets/views/view-restart.json)