curl -XPATCH \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/permissions?rev=2" -d \
  '{
      "@type": "Subtract",
      "permissions": [
        "newpermission/write"
      ]
    }'
