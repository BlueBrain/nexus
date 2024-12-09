curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/storages/myorg/myproject/s3" -d \
    '{
        "@type": "S3Storage",
        "default": false,
        "bucket": "test"
    }'