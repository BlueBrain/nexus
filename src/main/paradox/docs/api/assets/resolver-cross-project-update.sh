curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/resolvers/myorg/myproj/nxv:myresolver?rev=1" -d \
'{
  "@type": [
    "Resolver",
    "CrossProject"
  ],
  "projects": [
    "org1/project1",
    "org1/project2"
  ],
  "identities": [
    {
      "realm": "myrealm",
      "sub": "name"
    }
  ],
  "priority": 50
}'