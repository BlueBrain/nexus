curl -XPOST "https://nexus.example.com/v1/files/myorg/myproj?storage=nxv:mys3storage" -d \
'{
    "filename": "myfile.png",
    "path": "relative/path/to/myfile.png",
    "mediaType": "image/png"
}'
