curl -XPATCH -H "Content-Type: application/json" "https://nexus.example.com/v1/permissions?rev=2" -d \
'{
  "@type": "Subtract",
  "permissions": [
    "newpermission/write"
  ]
}'