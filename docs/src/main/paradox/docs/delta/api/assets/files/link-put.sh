curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/files/myorg/myproject/mylink?storage=remote&tag=mytag" -d \
   '{
      "path": "relative/path/to/myfile2.png",
      "metadata": {
        "name": "My File",
        "description": "a description of the file",
        "keywords": {
          "key1": "value1",
          "key2": "value2"
        }
      }
   }'