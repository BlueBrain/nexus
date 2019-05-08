curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/orgs/myorg?rev=1" -d \
'{
  "description": "A new description"
}'