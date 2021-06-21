curl -XPATCH \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/acls/org1?rev=3" -d \
    '{
      "@type": "Append",
      "acl": [
        {
          "permissions": [
            "own",
            "other"
          ],
          "identity": {
            "realm": "myrealm",
            "group": "a-group"
          }
        }
      ]
    }'
