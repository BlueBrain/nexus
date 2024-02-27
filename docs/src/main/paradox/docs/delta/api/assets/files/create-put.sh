curl -X PUT \
     -F "file=@/path/to/myfile.pdf;type=application/pdf" \
     -F 'metadata="{\"name\": \"descriptive name\", \"description\": \"a description\"}"'\
     "http://localhost:8080/v1/files/myorg/myproject/myfile?storage=remote&tag=mytag"