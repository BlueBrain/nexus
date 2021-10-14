curl -X POST \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/storages/myorg/myproject" -d \
    '{
        "@id": "https://bluebrain.github.io/nexus/vocabulary/local",
        "@type": "DiskStorage",
        "default": false
    }'