curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/schemas/myorg/myproj/myschema/base:e1729302-35b8-4d80-97b2-d63c984e2b5c/tags?rev=2" -d \
'{
  "tag": "mytag",
  "rev": 1
}'