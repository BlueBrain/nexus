curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview/projections/nxv:myprojection/_search" -d \
'{
    "query": {
        "term": {
            "name": {
                "value": "Muse"
            }
        }
    }
}'