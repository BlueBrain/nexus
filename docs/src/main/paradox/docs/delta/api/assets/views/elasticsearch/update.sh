curl -XPUT \
-H "Content-Type: application/json" \
"http://localhost:8080/v1/views/myorg/myproj/myview?rev=1" -d \
'{
    "@type": [
        "ElasticSearchView"
    ],
    "mapping": {
    "dynamic": false,
    "properties": {
      "@id": {
        "type": "keyword"
      },
      "@type": {
        "type": "keyword"
      },
      "name": {
        "type": "keyword"
      },
      "number": {
        "type": "long"
      },
      "bool": {
        "type": "boolean"
      }
    }
  },
  "pipeline": []
}'