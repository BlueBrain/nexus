curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/files/myorg/myproject/mylink?storage=remote" -d \
   '{
      "path": "relative/path/to/myfile2.png"
   }'