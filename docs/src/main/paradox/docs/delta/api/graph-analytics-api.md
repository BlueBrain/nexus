# Graph analytics

Graph analytics is a feature introduced by the `graph-analytics` plugin and rooted in the `/v1/graph-analytics/{org_label}/{project_label}` collection. 

It runs for each project and it parses and breaks down non-deprecated resources to analyse their structure.
For each of these resources, it extracts the following information:

* Its properties: their path and the type of the associated value
* Its relationships, that is to say the other resources in the same project it points to.

@@@ note { .tip title="Authorization notes" }	

When reading graph analytics, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

@@@ note { .warning }

The described endpoints are experimental and the responses structure might change in the future.

@@@

## Fetch relationships

Obtains all the @type relationships and their counts - defined by nodes and edges - existing on a project.

The edges are the properties linking different nodes, and the nodes are the resources containing a certain @type.
```
GET /v1/graph-analytics/{org_label}/{project_label}/relationships
```

**Example**

Request
:   @@snip [fetch-relationships.sh](assets/graph-analytics/fetch-relationships.sh)

Response
:   @@snip [fetched-relationships.json](assets/graph-analytics/fetched-relationships.json)


## Fetch properties

Obtains all the @type properties and their counts. 

The different between properties and relationships is
that properties are enclosed inside the same resource, while relationships are statements between different resources.
```
GET /v1/graph-analytics/{org_label}/{project_label}/properties/{type}
```

...where `{type}` is an IRI defining for which @type we want to retrieve the properties.

**Example**

Request
:   @@snip [fetch-properties.sh](assets/graph-analytics/fetch-properties.sh)

Response
:   @@snip [fetched-properties.json](assets/graph-analytics/fetched-properties.json)

## Fetch progress

```
GET /v1/graph-analytics/{org_label}/{project_label}/progress
```
It returns:

- the dateTime of the latest consumed event (`lastProcessedEventDateTime`).
- the number of consumed events (`eventsCount`).
- the number of consumed resources (`resourcesCount`). A resource might be made of multiple events (create, update, deprecate), so this number will always be smaller or equal to `eventsCount`.

**Example**

Request
:   @@snip [fetch-progress.sh](assets/graph-analytics/fetch-progress.sh)

Response
:   @@snip [fetched-progress.json](assets/graph-analytics/fetched-progress.json)

## Search

```
POST /v1/graph-analytics/{org_label}/{project_label}/_search
  {...}
```

Search documents that are in a given project's Graph Analytics view.

The supported payload is defined on the @link:[ElasticSearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-search.html#search-search-api-request-body){ open=new }.

**Example**

Request
:   @@snip [ga-search.sh](assets/graph-analytics/ga-search.sh)

Response
:   @@snip [ge-search.json](assets/graph-analytics/ga-search.json)

## Internals

In order to implement the described endpoints we needed a way to transform our data so that it would answer the desired questions in a performant manner.

The proposed solution was to stream our data, transform it and push it to a dedicated ElasticSearch index (one index per project). Then at query time we can run [term aggregations](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-bucket-terms-aggregation.html) in order to get the desired counts.

### Document structure

An example of the ElasticSearch Document looks as follows:

```json
{
  "@id": "http://example.com/Anna",
  "@type": "http://schema.org/Person",
  "_project": "myorg/myproject",
  "_rev": 4,
  "_deprecated": false,
  "_createdAt": "2023-06-01T00:00:00Z",
  "_createdBy": { "@type": "User", "realm": "bbp",  "subject": "Bob" },
  "_updatedAt": "2023-06-12T00:00:00Z",
  "_updatedBy": { "@type": "User", "realm": "bbp",  "subject": "Alice" },
  "properties": [
    {
      "dataType": "object",
      "path": "http://schema.org/address",
      "isInArray": false
    },
    {
      "dataType": "string",
      "path": "http://schema.org/address / http://schema.org/street",
      "isInArray": false
    },
    {
      "dataType": "number",
      "path": "http://schema.org/address / http://schema.org/zipcode",
      "isInArray": false
    },
    {
      "dataType": "object",
      "@id": "http://example.com/Robert",
      "path": "http://schema.org/brother",
      "isInArray": false
    },
    {
      "dataType": "string",
      "path": "http://schema.org/givenName",
      "isInArray": false
    },
    {
      "dataType": "object",
      "path": "http://schema.org/studies",
      "isInArray": true
    },
    {
      "dataType": "string",
      "path": "http://schema.org/studies / http://schema.org/name",
      "isInArray": true
    }
  ],
  "references": [
    {
      "found": true,
      "@id": "http://example.com/Robert"
    }
  ],
  "relationships": [
    {
      "dataType": "object",
      "@id": "http://example.com/Robert",
      "@type": "http://schema.org/Person",
      "path": "http://schema.org/brother",
      "isInArray": false
    }
  ],
  "remoteContexts": [
    {
      "@type": "ProjectRemoteContextRef",
      "iri": "https://bbp.epfl.ch/contexts/person",
      "resource": {
        "id": "https://bbp.epfl.ch/contexts/person",
        "project": "myorg/myproject",
        "rev": 1
      }
    }
  ]
}
```

... where:

- `properties` - Json Object Array: A flat collection of fields present on a resource.
- `references` - Json Object Array: A flat collection of fields present on a resource that could be potential candidates for relationships (they do have an @id).
- `relationships` - Json Object Array: A flat collection of @id(s) that have been found in other resources in the same project.
- `path` - String: The flat expanded path of a field present on a resource. A path of an embedded field will be encoded as follows: `parent / child`.
- `isInArray` - Boolean: Flag to inform whether the current path (or its parent) is part of an array.
- `dataType` - String: The type of the value present in the current path. Possible values are: string, numeric and boolean
- `found` - Boolean: Flag to inform whether an @id inside `references` has been resolved as a relationship.
- `remoteContexts` - Json Object Array: A collection of remote contexts detected during the JSON-LD resolution for this resource. 
  See the @ref:[Resources - Fetch remote contexts](resources-api.md#fetch-remote-contexts) operation to learn about the remote context types.