
# Train the recommender model

Your data is now prepared as a U-I matrix and you will use it to build a collaborative filtering recommendation model.

Collaborative filtering is a recommendation approach that is effectively based on the "wisdom of the crowd". It makes the assumption that, if two people share similar preferences, then the things that one of them prefers could be good recommendations to make to the other. In other words, if user A tends to like certain movies, and user B shares some of these preferences with user A, then the movies that user A likes, that user B has not yet seen, may well be movies that user B will also like.

In a similar manner, we can think about items as being similar if they tend to be rated highly by the same people, on average.

Hence these models are based on the combined, collaborative preferences and behavior of all users in aggregate. They tend to be very effective in practice (provided you have enough preference data to train the model). The ratings data you have is a form of explicit preference data, perfect for training collaborative filtering models.

Matrix factorization (MF) is a classical method to perform collaborative filtering model. The core idea of MF is to represent the ratings as a user-item ratings matrix. In the diagram below you will see this matrix on the left (with users as rows and movies as columns). The entries in this matrix are the ratings given by users to movies.

You may also notice that the matrix has missing entries because not all users have rated all movies. In this situation we refer to the data as sparse.

![Figure](assests/collaborative_filtering.png)

MF methods aim to find two much smaller matrices (one representing the users and the other the items) that, when multiplied together, re-construct the original ratings matrix as closely as possible. This is know as factorizing the original matrix, hence the name of the technique.

The two smaller matrices are called factor matrices (or latent features). The user and movie factor matrices are illustrated on the right in the diagram above. The idea is that each user factor vector is a compressed representation of the user's preferences and behavior. Likewise, each item factor vector is a compressed representation of the item. Once the model is trained, the factor vectors can be used to make recommendations, which is what you will do in the following sections.

The optimization of the MF can be done using different methods. In this example, we will use 2 popular methods:

- Alternating Least Squares (ALS)
The idea of ALS is that, we first hold one set of latent vectors constant. We then take the derivative of the loss function with respect to the other set of vectors. We set the derivative equal to zero  and solve for the non-constant vectors. With these new, solved-for user vectors in hand, we hold them constant, instead, and take the derivative of the loss function with respect to the previously constant vectors (the item vectors).
We alternate back and forth and carry out this two-step dance until convergence.
- Stochastic Gradient Descent (SGD)
The idea is also to take derivatives of the loss function. But instead we take the derivative with respect to each variable in the model. The “stochastic” aspect of the algorithm involves taking the derivative and updating feature weights one individual sample at a time.

Further reading:

[Explicit Matrix Factorization: ALS, SGD, and All That Jazz](https://www.ethanrosenthal.com/2016/01/09/explicit-matrix-factorization-sgd-als/)

[ALS Implicit Collaborative Filtering
](https://medium.com/radon-dev/als-implicit-collaborative-filtering-5ed653ba39fe)