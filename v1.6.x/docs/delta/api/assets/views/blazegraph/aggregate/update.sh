curl -X PUT \
     -H "Content-Type: application/json" \
     "https://localhost:8080/v1/views/myorg/myproj/nxv:myview?rev=1" \
     -d \
'{
  "@type": "AggregateSparqlView",
  "views": [
    {
      "project": "org/proj",
      "viewId": "https://bluebrain.github.io/nexus/vocabulary/myview"
    },
    {
      "project": "org3/proj3",
      "viewId": "https://bluebrain.github.io/nexus/vocabulary/yetanotherview"
    }
  ]
}'