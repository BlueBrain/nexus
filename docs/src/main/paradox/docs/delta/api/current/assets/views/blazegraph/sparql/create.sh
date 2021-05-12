curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/views/myorg/myproj" \
     -d \
'{
  "@id": "https://bluebrain.github.io/nexus/vocabulary/myview"
  "@type": "SparqlView",
  "resourceSchemas": [
    "https://bluebrain.github.io/nexus/vocabulary/some-schema"
  ],
  "resourceTypes": [
    "https://bluebrain.github.io/nexus/vocabulary/SomeType"
  ],
  "includeMetadata": false,
  "includeDeprecated": false,
  "permission": "my/permission",
}'