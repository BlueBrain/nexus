curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/type-hierarchy?rev=1" \
     -d \
'{
  "mapping": {
    "https://schema.org/VideoGame": [
      "https://schema.org/SoftwareApplication",
      "https://schema.org/CreativeWork",
      "https://schema.org/Thing"
    ]
  }
}'