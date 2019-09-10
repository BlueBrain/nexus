curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/permissions" -d \
'{
  "permissions": [
    "newpermission/read",
    "newpermission/write"
  ]
}'