curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/projects/myorg/myproject?rev=1" -d \
'{
  "description": "example project creation",
  "base": "https://nexus.example.com/v1/myorg/myproject/",
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