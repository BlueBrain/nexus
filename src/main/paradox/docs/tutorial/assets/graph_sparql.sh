# Average rating score for movies tagged as funny

PREFIX vocab: <https://sandbox.bluebrainnexus.io/v1/vocabs/testamld2019/movielens2/>
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>

Select  DISTINCT ?movieId (AVG(?rating) AS ?score)
 WHERE  {
    # Select movies
    ?movie a vocab:Movie.

    # Select their movieId value
    ?movie vocab:movieId ?movieId.

    # Keep movies with 'funny' tags
    ?tagnode vocab:movieId ?movieId.
    ?tagnode vocab:tag "funny".

    # Keep movies with ratings
    ?ratingNode vocab:movieId ?movidId.
    ?ratingNode vocab:rating ?rating.

}
GROUP BY ?movieId


