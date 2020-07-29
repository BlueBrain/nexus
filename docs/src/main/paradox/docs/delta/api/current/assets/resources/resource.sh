curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/resources/myorg/myproj/myschema" -d \
'{
  "@context": {
    "ex": "http://example.com/",
    "@vocab": "http://example.com/"
  },
  "@type": "ex:Custom",
  "name": "Alex",
  "number": 24,
  "bool": false
}'