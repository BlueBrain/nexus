curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/projects/myorg/myproject/tags?rev=2" -d \
'{
  "tag": "mytag",
  "rev": 1
}'