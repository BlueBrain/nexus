curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/views/myorg/bands/composite_view/tags?rev=2" \
     -d \
'{
  "tag": "mytag",
  "rev": 1
}'