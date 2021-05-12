curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/resolvers/myorg/myproj" \
     -d \
'{
  "@id": "https://bluebrain.github.io/nexus/vocabulary/myresolver",
  "@type": "CrossProject",
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