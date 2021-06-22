curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/resolvers/myorg/myproj/nxv:myresolver/tags?rev=2" \
     -d \
'{
  "tag": "mytag",
  "rev": 1
}'