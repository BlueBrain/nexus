curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/files/myorg/myproj/nxv:myfile/tags?rev=2" -d \
'{
  "tag": "mytag",
  "rev": 1
}'