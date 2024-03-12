curl -X POST \
     -F "file=@/path/to/myfile.jpg;type=image/jpeg" \
     -H 'x-nxs-file-metadata: {"name": "My File"}' \
     "http://localhost:8080/v1/files/myorg/myproject"