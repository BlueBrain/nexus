curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/projects/myorg/myproject" -d \
'{
  "description": "example project creation",
  "base": "https://nexus.example.com/v1/projects/myorg/myproject/",
  "vocab": "https://schema.org/",
  "apiMappings": [
    {
      "prefix": "person",
      "namespace": "http://example.com/some/person"
    },
    {
      "prefix": "schemas",
      "namespace": "https://bluebrain.github.io/nexus/schemas/"
    },
    {
      "prefix": "ex",
      "namespace": "http://example.com/"
    }
  ]
}'