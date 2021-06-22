curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/schemas/myorg/myproj/myschema/e1729302-35b8-4d80-97b2-d63c984e2b5c/tags?rev=2" \
     -d \
'{
  "tag": "mytag",
  "rev": 1
}'