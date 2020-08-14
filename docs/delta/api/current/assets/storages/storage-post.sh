curl -XPOST -H "Content-Type: application/json" "https://nexus.example.com/v1/storages/myorg/myproj" -d \
'{
  "@id": "nxv:mys3storage",
  "@type": "S3Storage",
  "default": false,
  "bucket": "mybucket",
  "endpoint": "https://s3.us-west-1.amazonaws.com",
  "accessKey": "AKIAIOSFODNN7EXAMPLE",
  "secretKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}'