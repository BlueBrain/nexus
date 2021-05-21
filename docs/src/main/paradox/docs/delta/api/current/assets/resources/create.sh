curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/resources/myorg/myproj/myschema" \
     -d \
'{
  "@context": {
    "ex": "http://localhost:8080/",
    "@vocab": "http://localhost:8080/"
  },
  "@type": "ex:Custom",
  "name": "Alex",
  "number": 24,
  "bool": false
}'