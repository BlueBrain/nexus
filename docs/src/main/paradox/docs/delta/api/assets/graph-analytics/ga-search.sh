curl -XPOST \
-H "Content-Type: application/json" \
"http://localhost:8080/v1/graph-analytics/myorg/myproj/_search" -d \
'{
  "query": {
    "term": {
      "@id": "https://example.com/person"
    }
  }
}'