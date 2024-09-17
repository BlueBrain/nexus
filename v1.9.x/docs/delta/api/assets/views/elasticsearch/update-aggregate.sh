curl -PUT \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/views/myorg/myproj/my_aggregate_view?rev=1" -d \
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
