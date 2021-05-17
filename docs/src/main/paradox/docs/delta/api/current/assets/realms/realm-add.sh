curl -XPUT \
  -H "Content-Type: application/json" \
  "http://localhost:8080/v1/realms/realm1" \
  -d '{
        "name":"Nexus Dev",
        "openIdConfig":"http://localhost:8080/auth/realms/bbp-test/.well-known/openid-configuration",
        "logo":"http://localhost:8080/logo.png"
      }'
