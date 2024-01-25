curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/resources/myorg/myproj/myschema/fd8a2b32-170e-44e8-808f-44a8cbbc49b0/tags?rev=2" \
     -d \
'{
  "tag": "mytag",
  "rev": 1
}'