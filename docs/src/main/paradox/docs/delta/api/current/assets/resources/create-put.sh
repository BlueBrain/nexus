curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/resources/myorg/myproj/myschema/base:fd8a2b32-170e-44e8-808f-44a8cbbc49b0" \
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