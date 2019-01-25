# Recommendation query

Now that we have loaded our recommendation model into Nexus, we will perform some recommendation by querying the models stored in Nexus.

* Fetch the embedding matrix stored in Nexus
```
r = nexus.files.fetch(org_label="YOUR ORG", project_label="YOUR PROJECT", file_id = sgd_vec_file_id, out_filepath='./sgd_vec.npy')
embedding_mat = np.load('./sgd_vec.npy')
```

* compute the similarity matrix of the movie
```
def cosine_similarity(vecs):
    sim = vecs.dot(vecs.T)
    norms = np.array([np.sqrt(np.diagonal(sim))])
    return sim / norms / norms.T

sim = cosine_similarity(embedding_mat)
```
* display top k similar movies
```
def display_top_k_movies_name(similarity, mapper, movie_idx, k=5):
    print('The recommended films for user who likes "%s"' % (mapper[movie_idx]))

    movie_indices = np.argsort(similarity[movie_idx,:])[::-1]
    images = ''
    k_ctr = 0
    # Start i at 1 to not grab the input movie
    i = 1
    while k_ctr < 5:
        movie = mapper[movie_indices[i]]
        print(' - ' + movie)
        k_ctr += 1
        i += 1
```

Let's now try to find some similar movies of "Heat", which has the movie id of 5.
```
movie_id = 5 # Heat
display_top_k_movies_name(sgd_sim, idx_to_movie, movie_id)
```
The recommended films for user who likes "Heat (1995)"
- Running With Scissors (2006)
- Wraith, The (1986)
- X-Men: The Last Stand (2006)
- Johnny Mnemonic (1995)
- Dumb and Dumberer: When Harry Met Lloyd (2003)