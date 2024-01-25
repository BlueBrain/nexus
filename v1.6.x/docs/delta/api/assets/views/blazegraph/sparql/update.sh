curl -X PUT \
     -H "Content-Type: application/json" \
     "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview?rev=1" \
     -d \
'{
  "@type": "SparqlView",
  "includeMetadata": false,
  "includeDeprecated": false,
  "permission": "my/permission",
}''