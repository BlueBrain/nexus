curl -X POST \
     -F "file=@/path/to/myfile.jpg;type=image/jpeg" \
     -F 'metadata="{\"name\": \"My File\"}"' \
     "http://localhost:8080/v1/files/myorg/myproject"