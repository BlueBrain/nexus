
# Prepare data

## Step 3: Prepare the data

To get the recommendation right, we must construct and transform the data correctly. This is usually a very important step so that you are sure your machine learning algorithm is consuming the correct data in a good way.

In the case of collaborative filtering using matrix factorization, the preparation of the data contains the following steps:

As in the U-I matrix we will have the user id and the item id as incremental integers, we will assign a unique number between (0, #users) to each user and do the same for movies. The mapping between the id in the U-I matrix will be stored, which can be further used to query the recommendation.
```
movie_mapping = dict( enumerate(df_rating.movieId.astype('category').cat.categories) )
inv_movie_mapping = {v: k for k, v in movie_mapping.items()}

df_rating.userId = df_rating.userId.astype('category').cat.codes.values
df_rating.movieId = df_rating.movieId.astype('category').cat.codes.values
```

Then, we will create the U-I matrix by assigning each user's rating to each movie on a zero matrix created using numpy.
```
n_users = df_rating.userId.unique().shape[0]
n_items = df_rating.movieId.unique().shape[0]

# Create r_{ui}, our ratings matrix
ratings = np.zeros((n_users, n_items))
for row in df_rating.itertuples():
    ratings[row[1]-1, row[2]-1] = row[3]
```

Finally, we will split the data into training and testing. This is done by removing 10 ratings for each user and assign them to the test set.
```
def train_test_split(ratings):
    test = np.zeros(ratings.shape)
    train = ratings.copy()
    for user in range(ratings.shape[0]):
        test_ratings = np.random.choice(ratings[user, :].nonzero()[0],
                                        size=10, replace=False)
        train[user, test_ratings] = 0.
        test[user, test_ratings] = ratings[user, test_ratings]

    # Test and training are truly disjoint
    assert(np.all((train * test) == 0))
    return train, test

train, test = train_test_split(ratings)
```