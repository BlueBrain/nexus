# Select 5 movies along with their average rating
$ nexus search --view https://nexus-sandbox.io/v1/views/amld2019/movielens/graph/sparql -d \
'PREFIX vocab: <https://nexus-sandbox.io/v1/vocabs/amld2019/movielens/>
 Select ?m ?title  AVG(?rating)
   WHERE  {
      ?m     a       vocab:Movie.
      ?m vocab:genres ?genre.
      ?m vocab:title ?title.
      ?m vocab:title ?title.
}
ORDER BY vocab:title DESC
LIMIT 5'