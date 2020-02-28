curl -XPUT "https://nexus.example.com/v1/files/myorg/myproj/nxv:myfile.png?storage=nxv:mys3storage" -d \
'{
    "filename": "myfile.png",
    "path": "relative/path/to/myfile.png",
    "mediaType": "image/png"
}'
