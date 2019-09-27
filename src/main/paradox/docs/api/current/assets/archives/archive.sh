curl -L "https://nexus.example.com/v1/archives/myorg/myproject" -H "Content-Type: application/json" -H "Accept: */*" -d '{
    "resources" : [
        {
            "@type": "Resource",
            "resourceId": "https://nexus.example.com/resource1",
            "rev": 2
        },
        {
            "@type": "Resource",
            "resourceId": "https://nexus.example.com/resource2",
            "project": "myorg/myproject2",
            "originalSource": false
        },
        {
            "@type": "File",
            "resourceId": "https://nexus.example.com/resource2",
            "project": "myorg/myproject2",
            "path": "my/custom/path/resource2.json"
        }
    ]
}
'