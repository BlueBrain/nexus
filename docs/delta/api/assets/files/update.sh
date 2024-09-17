curl -X PUT \
   -F "file=@/path/to/myfile2.pdf;type=application/pdf" \
   -H 'x-nxs-file-metadata: {"name": "My File"}' \
   "http://localhost:8080/v1/files/myorg/myproject/myfile?rev=1"