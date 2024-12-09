curl -XDELETE \
-H "Content-Type: application/json" \
"http://localhost:8080/v1/views/myorg/myproj/myview/_pit" -d \
'{
  "id": "xxx"
}'