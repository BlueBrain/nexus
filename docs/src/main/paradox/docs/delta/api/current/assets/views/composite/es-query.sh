curl -X POST \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/views/myorg/bands/composite_view/projections/music:es/_search" \
     -d \
'{
    "query": {
        "match": {
            "name": "Muse"
        }
    }
}'