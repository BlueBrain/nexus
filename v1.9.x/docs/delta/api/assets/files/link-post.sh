curl -X POST \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/files/myorg/myproject?storage=remote" -d \
   '{
      "path": "relative/path/to/myfile.png",
      "filename": "myfile.png",
      "mediaType": "image/png"
   }'