curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/resolvers/myorg/myproj/nxv:myresolver" \
     -d \
'{
  "@type": "CrossProject",
  "projects": [
    "org1/project1",
    "org1/project2"
  ],
  "useCurrentCaller": true,
  "priority": 50
}'