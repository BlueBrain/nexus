# Query the data from Nexus

Now we will start pulling movielens data that has been ingested into your Nexus project previously.

For building a classical recommendation system using matrix factorization, we will need a user-by-item matrix where
nonzero elements of the matrix are ratings that a user has given an item. To do that, we will

1.   Query all the rating data for building a U-I matrix
2.   Query the movie id data for the recomendation

SPARQL is an RDF query language which is able to retrieve and manipulate data stored in Resource Description Framework
(RDF) format. Given that all the movielens data is put into the knowledge graph meaning they are inter-connected, it is
straightforward to query the data using SPARQL.

## Setting up the environment for SPARQL

We will first need to install a python wrapper around a SPARQL service. It helps in creating the query URI and,
possibly, convert the result into a more manageable format.

```
!pip install git+https://github.com/RDFLib/sparqlwrapper
```

Once it is installed, we need to set up the SPARQL wrapper
```
sparql_client = SPARQLWrapper('YOUR_ENDPOINT_URL')
sparql_client.addCustomHttpHeader("Content-Type", "application/sparql-query")
sparql_client.addCustomHttpHeader("Authorization","Bearer YOUR_TOKEN")
sparql_client.setMethod(POST)
sparql_client.setReturnFormat(JSON)
sparql_client.setRequestMethod(POSTDIRECTLY)
```

Let's now write a SPARQL query to fetch the movie rating data.
```
PREFIX vocab: <YOUR DEPLOYMENT URL/vocabs/YOUR ORG/YOUR PROJECT/>

Select ?userId ?movieId ?rating
WHERE  {
?ratingNode a vocab:Rating.
?ratingNode vocab:movieId ?movieId.
?ratingNode vocab:rating ?rating.
?ratingNode vocab:userId ?userId.
}
```
Then, we can pass this query to the Sparql Wrapper to query data.
```
def query(query, sparql_client):
sparql_client.setQuery(query)
result_object = sparql_client.query()
return result_object._convertJSON()
```

As querying large amount of data from the Blazegraph can be very computationally hearvy, we might want to do it in batches. To do so, first we need to get the total number of the data by querying the ElasticSearch View.
```
es_query = {
"query": {
"terms" : {"@type":["YOUR_DEPLOYMENT_URL/vocabs/YOUR_ORG/YOUR_PROJECT/Rating"]}
}
}
es_payload = nexus.views.query_es(org_label="YOUR ORG", project_label="YOUR PROJECT", view_id='documents', query=es_query)
total_items = es_payload['hits']['total']
```

Then, we could query the data in batches using a loop.
```
for i in range(batches):
start_idx = i * batch_size
if i == batches - 1:
size = total_items % batch_size
else:
size = batch_size

sparql_query = """
PREFIX vocab: <YOUR DEPLOYMENT URL/vocabs/YOUR ORG/YOUR PROJECT/>

Select ?userId ?movieId ?rating
WHERE  {
?ratingNode a vocab:Rating.
?ratingNode vocab:movieId ?movieId.
?ratingNode vocab:rating ?rating.
?ratingNode vocab:userId ?userId.
}
ORDER BY ASC(?userId) ASC (?movieId)
OFFSET %start_idx%
LIMIT %batch_size%""".replace('%start_idx%', str(start_idx)).replace('%batch_size%', str(size))

results = query(sparql_query, sparql_client)
```

Similarly, we can also load the movie id data so that we can have the mapping between the movie id and movie title.
```
df_movie = load_movie_from_nexus()
```

