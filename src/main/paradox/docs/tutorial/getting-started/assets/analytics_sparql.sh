# Average rating score for movies tagged as funny
nexus views query-sparql --data \
'
PREFIX vocab: <https://sandbox.bluebrainnexus.io/v1/vocabs/tutorialnexus/$PROJECTLABEL/>
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>

Select (AVG(?ratingvalue) AS ?score)
 WHERE  {
    # Select movies
    ?movie a vocab:Movie.

    # Select their movieId values
    ?movie vocab:movieId ?movieId.

    # Keep movies with 'funny' tags
    ?tag a vocab:Tag.
    ?tag vocab:movieId ?movieId.
    ?tag vocab:tag ?tagvalue.
    FILTER(?tagvalue = "funny").
    
    # Keep movies with ratings
    ?rating a vocab:Rating.
    ?rating vocab:movieId ?ratingmovieId.
    FILTER(xsd:integer(?ratingmovieId) = xsd:integer(?movieId))
    ?rating vocab:rating ?ratingvalue.

}'
