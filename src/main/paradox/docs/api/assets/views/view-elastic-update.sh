curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview?rev=1" -d \
'{
  "@type": [
    "ElasticSearchView",
    "Alpha"
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
  "sourceAsText": false,
  "resourceSchemas": "https://bluebrain.github.io/nexus/schemas/myschema"
}'