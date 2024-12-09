curl -X POST \
     -H "Content-Type: application/sparql-query" \
     -H "Accept: application/n-triples" \
     "http://localhost:8080/v1/views/myorg/bands/composite_view/projections/_/sparql" \
     -d \
'prefix music: <http://music.com/>
CONSTRUCT {
    ?s ?p ?o
} WHERE {
    ?s ?p ?o FILTER(?s = music:absolution)
}'