curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/views/myorg/myproj/nxv:myview" \
     -d \
'{
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