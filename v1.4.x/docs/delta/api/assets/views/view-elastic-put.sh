curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview" -d \
'{
  "@type": [
    "ElasticSearchView",
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
}'