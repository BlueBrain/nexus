curl -X PUT \
   -F "file=@/path/to/myfile2.pdf;type=application/pdf" \
   "http://localhost:8080/v1/files/myorg/myproject/myfile?rev=1"