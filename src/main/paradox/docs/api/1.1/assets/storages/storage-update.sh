curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/storages/myorg/myproj/nxv:mys3storage" -d \
'{
  "@type": "S3Storage",
  "default": true,
  "bucket": "mybucket",
  "endpoint": "https://s3.us-west-1.amazonaws.com",
  "accessKey": "AKIAIOSFODNN7EXAMPLE",
  "secretKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}'