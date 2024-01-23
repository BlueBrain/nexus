curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/orgs/myorg"  \
   -d '{"description": "organization description"}'