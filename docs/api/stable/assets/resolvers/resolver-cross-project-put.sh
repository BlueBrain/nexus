curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/resolvers/myorg/myproj/nxv:myresolver" -d \
'{
  "@type": [
    "CrossProject"
  ],
  "projects": [
    "org1/project1",
    "org1/project2"
  ],
  "identities": [
    {
      "realm": "myrealm",
      "subject": "name"
    }
  ],
  "priority": 50
}'