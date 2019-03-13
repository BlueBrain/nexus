# Select 5 movies sorted by title in descending order
$ nexus search --view https://sandbox.bluebrainnexus.io/v1/views/amld2019/movielens/graph/sparql -d \
'PREFIX vocab: <https://sandbox.bluebrainnexus.io/v1/vocabs/amld2019/movielens/>
 Select ?m ?title
   WHERE  {
      ?m     a       vocab:Movie.
      ?m vocab:title ?title.
}
ORDER BY vocab:title DESC
LIMIT 5'