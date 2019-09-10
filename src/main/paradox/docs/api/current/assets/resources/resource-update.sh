curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/resources/myorg/myproj/myschema/base:fd8a2b32-170e-44e8-808f-44a8cbbc49b0?rev=1" -d \
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