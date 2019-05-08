curl -XPATCH -H "Content-Type: application/json" "https://nexus.example.com/v1/permissions?rev=3" -d \
'{
  "@type": "Append",
  "permissions": [
    "newpermission/create"
  ]
}'