curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj" -d \
'{
  "@id": "https://bluebrain.github.io/nexus/vocabulary/myview",
  "@type": [
    "View",
    "ElasticView",
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
  "resourceSchemas": "nxs:myschema"
}'