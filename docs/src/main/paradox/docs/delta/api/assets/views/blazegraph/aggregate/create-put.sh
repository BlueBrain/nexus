curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/views/myorg/myproj/nxv:myview" \
     -d \
'{
  "@type": "AggregateSparqlView",
  "views": [
    {
      "project": "org/proj",
      "viewId": "https://bluebrain.github.io/nexus/vocabulary/myview"
    },
    {
      "project": "org2/proj2",
      "viewId": "https://bluebrain.github.io/nexus/vocabulary/myotherview"
    }
  ]
}'