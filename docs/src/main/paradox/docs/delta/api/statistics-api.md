# Statistics

Statistics are a functionality introduced by the statistics plugin and rooted in the `/v1/statistics/{org_label}/{project_label}` collection. 
They provide ways to get insights about the data and their relationships in terms of types (@type field).
  

@@@ note { .tip title="Authorization notes" }	

When reading statistics, the caller must have `resources/read` permissions on the current path of the project or the 
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
GET /v1/statistics/{org_label}/{project_label}/relationships
```

**Example**

Request
:   @@snip [fetch-relationships.sh](assets/statistics/fetch-relationships.sh)

Response
:   @@snip [fetched-relationships.json](assets/statistics/fetched-relationships.json)


## Fetch properties

Obtains all the @type properties and their counts. 

The different between properties and relationships is
that properties are enclosed inside the same resource, while relationships are statements between different resources.
```
GET /v1/statistics/{org_label}/{project_label}/properties/{type}
```

...where `{type}` is an IRI defining for which @type we want to retrieve the properties.

**Example**

Request
:   @@snip [fetch-properties.sh](assets/statistics/fetch-properties.sh)

Response
:   @@snip [fetched-properties.json](assets/statistics/fetched-properties.json)

## Fetch progress

```
GET /v1/statistics/{org_label}/{project_label}/progress
```
It returns:

- the dateTime of the latest consumed event (`lastProcessedEventDateTime`).
- the number of consumed events (`eventsCount`).
- the number of consumed resources (`resourcesCount`). A resource might be made of multiple events (create, update, deprecate), so this number will always be smaller or equal to `eventsCount`.

**Example**

Request
:   @@snip [fetch-progress.sh](assets/statistics/fetch-progress.sh)

Response
:   @@snip [fetched-progress.json](assets/statistics/fetched-progress.json)

## Internals

In order to implement the described endpoints we needed a way to transform our data so that it would answer the desired questions in a performant manner.

The proposed solution was to stream our data, transform it and push it to a dedicated ElasticSearch index (one index per project). Then at query time we can run [term aggregations](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-bucket-terms-aggregation.html) in order to get the desired counts.

### Document structure

An example of the ElasticSearch Document looks as follows:

```json
{
    "took": 8,
    "timed_out": false,
    "_shards": {
        "total": 1,
        "successful": 1,
        "skipped": 0,
        "failed": 0
    },
    "hits": {
        "total": {
            "value": 8,
            "relation": "eq"
        },
        "max_score": 1.0,
        "hits": [
            {
                "_index": "test_myorg%2fmyproject_statistics",
                "_type": "_doc",
                "_id": "http://example.com/Anna",
                "_score": 1.0,
                "_source": {
                    "@type": "http://schema.org/Person",
                    "@id": "http://example.com/Anna",
                    "properties": [
                        {
                            "path": "http://schema.org/address",
                            "isInArray": false
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/address / http://schema.org/street",
                            "isInArray": false
                        },
                        {
                            "dataType": "numeric",
                            "path": "http://schema.org/address / http://schema.org/zipcode",
                            "isInArray": false
                        },
                        {
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
                            "path": "http://schema.org/studies",
                            "isInArray": true
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/studies / http://schema.org/name",
                            "isInArray": true
                        },
                        {
                            "path": "http://schema.org/studies",
                            "isInArray": true
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/studies / http://schema.org/name",
                            "isInArray": true
                        }
                    ],
                    "relationshipCandidates": [
                        {
                            "found": true,
                            "@id": "http://example.com/Robert",
                            "path": "http://schema.org/brother",
                            "isInArray": false
                        }
                    ],
                    "relationships": [
                        {
                            "@id": "http://example.com/Robert",
                            "@type": "http://schema.org/Person",
                            "path": "http://schema.org/brother",
                            "isInArray": false
                        }
                    ]
                }
            },
            {
                "_index": "test_myorg%2fmyproject_statistics",
                "_type": "_doc",
                "_id": "http://example.com/Sam",
                "_score": 1.0,
                "_source": {
                    "@type": "http://schema.org/Person",
                    "@id": "http://example.com/Sam",
                    "properties": [
                        {
                            "path": "http://schema.org/address",
                            "isInArray": false
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/address / http://schema.org/street",
                            "isInArray": false
                        },
                        {
                            "dataType": "numeric",
                            "path": "http://schema.org/address / http://schema.org/zipcode",
                            "isInArray": false
                        },
                        {
                            "@id": "http://example.com/Pedro",
                            "path": "http://schema.org/brother",
                            "isInArray": false
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/givenName",
                            "isInArray": false
                        },
                        {
                            "path": "http://schema.org/studies",
                            "isInArray": true
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/studies / http://schema.org/name",
                            "isInArray": true
                        },
                        {
                            "path": "http://schema.org/studies",
                            "isInArray": true
                        },
                        {
                            "dataType": "string",
                            "path": "http://schema.org/studies / http://schema.org/name",
                            "isInArray": true
                        }
                    ],
                    "relationshipCandidates": [
                        {
                            "found": false,
                            "@id": "http://example.com/Pedro",
                            "path": "http://schema.org/brother",
                            "isInArray": false
                        }
                    ],
                    "relationships": []
                }
            },
            {
                "_index": "test_myorg%2fmyproject_statistics",
                "_type": "_doc",
                "_id": "http://example.com/Robert",
                "_score": 1.0,
                "_source": {
                    "relationships": [
                        {
                            "path": "http://schema.org/brother",
                            "found": true,
                            "isInArray": false,
                            "@type": [
                                "http://schema.org/Person"
                            ],
                            "@id": "http://example.com/Sam"
                        }
                    ],
                    "@type": "http://schema.org/Person",
                    "@id": "http://example.com/Robert",
                    "relationshipCandidates": [
                        {
                            "path": "http://schema.org/brother",
                            "found": true,
                            "isInArray": false,
                            "@type": [
                                "http://schema.org/Person"
                            ],
                            "@id": "http://example.com/Sam"
                        }
                    ],
                    "properties": [
                        {
                            "path": "http://schema.org/address",
                            "isInArray": false
                        },
                        {
                            "path": "http://schema.org/address / http://schema.org/street",
                            "isInArray": false,
                            "dataType": "string"
                        },
                        {
                            "path": "http://schema.org/address / http://schema.org/zipcode",
                            "isInArray": false,
                            "dataType": "numeric"
                        },
                        {
                            "path": "http://schema.org/brother",
                            "isInArray": false,
                            "@id": "http://example.com/Sam"
                        },
                        {
                            "path": "http://schema.org/givenName",
                            "isInArray": false,
                            "dataType": "string"
                        },
                        {
                            "path": "http://schema.org/studies",
                            "isInArray": true
                        },
                        {
                            "path": "http://schema.org/studies / http://schema.org/name",
                            "isInArray": true,
                            "dataType": "string"
                        },
                        {
                            "path": "http://schema.org/studies",
                            "isInArray": true
                        },
                        {
                            "path": "http://schema.org/studies / http://schema.org/name",
                            "isInArray": true,
                            "dataType": "string"
                        }
                    ]
                }
            }
        ]
    }
}
```

... where:

- `properties` - Json Object Array: A flatten collection of fields present on a resource.
- `relationshipCandidates` - Json Object Array: A flatten collection of fields present on a resource that could be potential candidates for relationships (they do have an @id).
- `relationships` - Json Object Array: A flatten collection of @id(s) that have been found in other resources.
- `path` - String: The flatten expanded path of a field present on a resource. A path of an embedded field will be encoded as follows: `parent / child`.
- `isInArray` - Boolean: Flag to inform whether the current path (or its parent) is part of an array.
- `dataType` - String: The type of the value present in the current path. Possible values are: string, numeric and boolean
- `found` - Boolean: Flag to inform whether a path inside `relationshipCandidates` is now promoted to `relationship`.