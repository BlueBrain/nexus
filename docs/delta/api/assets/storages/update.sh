curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/storages/myorg/myproject/remote?rev=1" -d \
    '{
        "@type": "RemoteDiskStorage",
        "default": false,
        "folder": "test-updated"
    }'