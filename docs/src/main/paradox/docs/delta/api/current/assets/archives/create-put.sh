curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/archives/myorg/myproject/myarchive" \
     -d '{
    "resources" : [
        {
            "@type": "Resource",
            "resourceId": "http://localhost:8080/resource1",
            "rev": 2
        },
        {
            "@type": "Resource",
            "resourceId": "http://localhost:8080/resource2",
            "project": "myorg/myproject2",
            "originalSource": false
        },
        {
            "@type": "File",
            "resourceId": "http://localhost:8080/resource2",
            "project": "myorg/myproject2",
            "path": "my/custom/path/resource2.json"
        }
    ]
}
'