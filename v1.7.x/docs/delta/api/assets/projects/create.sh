curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/projects/myorg/myproject" \
   -d '{
    "description": "description",
    "vocab": "https://schema.org/",
    "apiMappings": [
        {
            "prefix": "my",
            "namespace": "http://example.com/my"
        }
    ]
   }'