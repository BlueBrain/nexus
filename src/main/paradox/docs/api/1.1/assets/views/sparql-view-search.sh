curl -XPOST -H "Content-Type: application/sparql-query" "https://nexus.example.com/v1/views/myorg/myproj/graph/sparql" -d \
'SELECT ?s where {?s ?p ?o} LIMIT 2'