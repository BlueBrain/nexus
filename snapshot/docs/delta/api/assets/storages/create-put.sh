curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/storages/myorg/myproject/remote" -d \
    '{
        "@type": "RemoteDiskStorage",
        "default": false,
        "folder": "test"
    }'