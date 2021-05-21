curl -XPATCH \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/acls/org1?rev=2" -d \
  '{
  "@type": "Subtract",
  "acl": [
    {
      "permissions": [
        "projects/read"
      ],
      "identity": {
        "group": "a-group",
        "realm": "myrealm"
      }
    }
  ]
}'
