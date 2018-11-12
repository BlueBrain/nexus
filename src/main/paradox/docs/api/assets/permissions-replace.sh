curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/permissions?rev=1" -d \
'{
  "permissions": [
    "read",
    "write"
  ]
}'