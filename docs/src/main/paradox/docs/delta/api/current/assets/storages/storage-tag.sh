curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/storages/myorg/myproj/nxv:mys3storage/tags?rev=2" -d \
'{
  "tag": "mytag",
  "rev": 1
}'