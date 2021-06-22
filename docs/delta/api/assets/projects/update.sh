curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/projects/myorg/myproject?rev=1" \
   -d '{
    "description": "updated description",
    "vocab": "https://schema.org/"
   }'