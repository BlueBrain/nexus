curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview/tags?rev=2" -d \
'{
  "tag": "mytag",
  "rev": 1
}'