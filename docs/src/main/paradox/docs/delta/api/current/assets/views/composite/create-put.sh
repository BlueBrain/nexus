curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/views/myorg/bands/composite_view" \
     -d \
'{
    "@type": "CompositeView",
    "sources": [
        {
            "@id": "http://music.com/source_bands",
            "@type": "ProjectEventStream",
            "resourceTypes": [ "http://music.com/Band" ]
        },
        {
            "@id": "http://music.com/source_albums",
            "@type": "CrossProjectEventStream",
            "project": "myorg/albums",
            "identities": {
                "@type": "Anonymous"
            },
            "resourceTypes": [ "http://music.com/Album" ]
        },
        {
            "@id": "http://music.com/source_songs",
            "@type": "RemoteProjectEventStream",
            "endpoint": "http://localhost:8080/v1",
            "project": "myorg/songs",
            "resourceTypes": [ "http://music.com/Song" ]
        }
    ],
    "projections": [
        {
            "@id": "http://music.com/es",
            "@type": "ElasticSearchProjection",
            "mapping": {
                "properties": {
                    "@type": {
                        "type": "keyword"
                    },
                    "@id": {
                        "type": "keyword"
                    },
                    "name": {
                        "type": "keyword"
                    },
                    "genre": {
                        "type": "keyword"
                    },
                    "start": {
                        "type": "integer"
                    },
                    "album": {
                        "type": "nested",
                        "properties": {
                            "title": {
                                "type": "text"
                            }
                        }
                    }
                },
                "dynamic": false
            },
            "query": "prefix music: <http://music.com/> CONSTRUCT {{resource_id} music:name ?bandName ; music:genre      ?bandGenre ; music:start      ?bandStartYear ; music:album      ?albumId . ?albumId music:title ?albumTitle . } WHERE {{resource_id}   music:name       ?bandName ; music:start ?bandStartYear; music:genre ?bandGenre . OPTIONAL {{resource_id} ^music:by ?albumId . ?albumId        music:title   ?albumTitle . } }",
            "context": {
                "@base": "http://music.com/",
                "@vocab": "http://music.com/"
            },
            "resourceTypes": [ "http://music.com/Band" ]
        },
        {
            "@id": "http://music.com/sparql",
            "@type": "SparqlProjection",
            "query": "prefix xsd: <http://www.w3.org/2001/XMLSchema#> prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT { {resource_id}             music:name               ?albumTitle ; music:length             ?albumLength ; music:numberOfSongs      ?numberOfSongs } WHERE {SELECT ?albumReleaseDate ?albumTitle (sum(xsd:integer(?songLength)) as ?albumLength) (count(?albumReleaseDate) as ?numberOfSongs) WHERE {OPTIONAL { {resource_id}           ^music:on / music:length   ?songLength } {resource_id} music:released             ?albumReleaseDate ; music:title                ?albumTitle . } GROUP BY ?albumReleaseDate ?albumTitle }",
            "context": {
                "@base": "http://music.com/",
                "@vocab": "http://music.com/"
            },
            "resourceTypes": [ "http://music.com/Album" ]
        }
    ],
    "rebuildStrategy": {
        "@type": "Interval",
        "value": "10 minute"
    }
}'