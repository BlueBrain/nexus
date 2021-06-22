curl -XPOST \
-H "Content-Type: application/json" \
"http://localhost:8080/v1/views/myorg/myproj/my_aggregate_view/_search" -d \
'{
  "query": {
    "term": {
      "@type": "https://bluebrain.github.io/nexus/vocabulary/ElasticSearchView"
    }
  }
}'