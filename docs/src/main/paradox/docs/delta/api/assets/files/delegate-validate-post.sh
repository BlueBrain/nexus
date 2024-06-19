curl -X POST \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/delegate/files/myorg/myproject/validate?storage=mys3storage" -d \
   '{
      "filename": "myfile.png",
      "mediaType": "image/png",
      "metadata": {
        "name": "My File",
        "description": "a description of the file",
        "keywords": {
          "key1": "value1",
          "key2": "value2"
        }
      }
   }'