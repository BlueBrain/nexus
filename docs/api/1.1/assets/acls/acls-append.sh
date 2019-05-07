curl -XPATCH -H "Content-Type: application/json" "https://nexus.example.com/v1/acls/org1?rev=3" -d  \
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