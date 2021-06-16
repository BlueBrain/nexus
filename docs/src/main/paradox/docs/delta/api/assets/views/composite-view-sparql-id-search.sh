curl -XPOST -H "Content-Type: application/sparql-query" "https://nexus.example.com/v1/views/myorg/myproj/nxv:myview/projections/nxv:album/sparql" -d \
'SELECT ?s where {?s ?p ?o} LIMIT 2'