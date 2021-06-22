curl -XPOST \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/views/myorg/myproj" -d \
'{
  "@type": "AggregateElasticSearchView",
  "views": [
    {
      "project": "myorg/myproj",
      "viewId": "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex"
    },
    {
      "project": "myorg/myproj2",
      "viewId": "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex"
    }
  ]
}'
