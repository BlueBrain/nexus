curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/realms/realm1?rev=1" \
    -d '{"name":"realm1","openidConfiguration":"http://nexus.example.com/auth/realms/bbp-test/.well-known/openid-configuration","requiredScopes":["openid","nexus", "another"]}'