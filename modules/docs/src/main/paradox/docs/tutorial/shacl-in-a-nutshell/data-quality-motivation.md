## Motivation

We've loaded entities (movies, ratings, tags) from the small version of the MovieLens dataset during the [Quick start tutorial](). Let now have closer look at the different properties of the loaded entities.



The following payloads show respectively the shape of a movie entity from the MovieLens dataset and as retrieved from Nexus.

```csv
movieId,title,genres
1,Toy Story (1995),Adventure|Animation|Children|Comedy|Fantasy
```


```json
{
    "@context": [
        {
            "@base": "$NEXUS-URL/resources/amld2019/$USERNAME/_/",
            "@vocab": "$NEXUS-URL/vocabs/amld2019/$USERNAME/"
        },
        "https://bluebrain.github.io/nexus/contexts/resource.json"
    ],
    "@id": "Movie_1",
    "@type": "Movie",
    "genres": "Adventure|Animation|Children|Comedy|Fantasy",
    "movieId": 1,
    "title": "Toy Story (1995)"
    "_rev": 1,
    "_deprecated": false
}
```

As we can see, there is a year value (1995) as part of the movie title (Toy Story (1995)). What's the meaing of this year ? A release year? If yes in which country?
With the shape above, movies clearly can't be easily filtered by release date as that information is not explicitly asserted.
Freshness is known to play an important role in search and recommendation relevance for users so it might be beneficial to have release date as separate information.

Also the movie genres value is not trivial to use because of the | character as genre value delimiter. Filtering movies by genre is also tricky in this case.

How can the movie data be managed in a knowledge graph within Nexus with the guarantee that:

* the release year is explicitly asserted as a first class citizen attribute of a movie entity rather than being hidden in the title ?
* the genres are splitted ?
* No movie payload gets in the knowledge graph unless it conforms to some quality criteria ?

That's where the [W3C Shape Constraint Language (SHACL)](https://www.w3.org/TR/shacl/) comes into play.