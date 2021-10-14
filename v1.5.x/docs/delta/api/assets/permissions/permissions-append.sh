curl -XPATCH \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/permissions?rev=3" -d \
  '{
  "@type": "Append",
  "permissions": [
    "newpermission/create"
  ]
}'
