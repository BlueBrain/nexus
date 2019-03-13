# Select 5 ratings sorted by creation date in descending order
nexus views query-sparql --data \
'
PREFIX vocab: <https://sandbox.bluebrainnexus.io/v1/vocabs/tutorialnexus/$PROJECTLABEL/>
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>

Select ?userId ?moviedId ?rating ?createdAt
 WHERE  {

    ?ratingNode a vocab:Rating.
    ?ratingNode nxv:createdAt ?createdAt.
    ?ratingNode vocab:userId  ?userId.
    ?ratingNode vocab:movieId ?moviedId.
    ?ratingNode vocab:rating  ?rating.
}
ORDER BY DESC (?creationDate)
LIMIT 5'