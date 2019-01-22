# Select 5 movies ordered by title in descending order
$ nexus search --view $NEXUS-URL/views/amld2019/movielens/graph/sparql -d \
'PREFIX vocab: <$NEXUS-URL/vocabs/amld2019/movielens/>
 Select ?m ?title
   WHERE  {
      ?m     a       vocab:Movie.
      ?m vocab:title ?title.
}
ORDER BY vocab:title DESC
LIMIT 5'
