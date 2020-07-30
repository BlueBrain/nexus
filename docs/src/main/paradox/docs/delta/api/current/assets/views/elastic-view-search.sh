curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview/_search" -d \
'{
  "query": {
    "term": {
      "_deprecated": true
    }
  }
}'