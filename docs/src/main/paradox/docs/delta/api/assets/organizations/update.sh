curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/orgs/myorg?rev=1"  \
   -d '{"description": "organization updated description"}'