curl -XPATCH \
  -H "Content-Type: application/json" \
  -H "Authentication: Bearer ***" \
  "http://localhost:8080/v1/permissions?rev=3" -d \
  '{
  "@type": "Append",
  "permissions": [
    "newpermission/create"
  ]
}'
