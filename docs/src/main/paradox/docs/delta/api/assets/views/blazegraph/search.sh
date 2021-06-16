curl -XPOST \
     -H "Content-Type: application/sparql-query" \
     "https://localhost:8080/v1/views/myorg/myproj/nxv:myview/sparql" \
     -d 'SELECT ?s where {?s ?p ?o} LIMIT 2'