# Select 5 movies sorted by title in descending order
$ nexus search --view $NEXUS-URL/views/amld2019/movielens/graph/sparql -d \
'PREFIX vocab: <$NEXUS-URL/vocabs/amld2019/movielens/>
 Select ?m ?title
   WHERE  {
      ?m     a       vocab:Movie.
      ?m vocab:title ?title.
}
ORDER BY vocab:title DESC
LIMIT 5'


# Select 5 Comedy and Action movies sorted by title
$ nexus search --view $NEXUS-URL/views/amld2019/movielens/graph/sparql -d \
'PREFIX vocab: <$NEXUS-URL/vocabs/amld2019/movielens/>
 Select ?m ?title
   WHERE  {
      ?m     a       vocab:Movie.
      ?m vocab:genres ?genre.
      ?m vocab:title ?title.
      FILTER (("Comedy" IN ?genre) AND ("Action" IN ?genre)).
}
ORDER BY vocab:title DESC
LIMIT 5'