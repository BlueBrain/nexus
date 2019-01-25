# Step 5: Push the embedding matrix to Nexus

Now that we have the models trained, we can now push the embedding matrices back to Nexus.

First, we will create a file that stores the movie embedding matrix of the SGD model in Nexus by using the SDK.
We then keep the @id of the created file.

```
embedding_payload = {
    'modelName': 'ALS',
    'fileId': sgd_vec_file_id
}
r = nexus.resources.create(org_label="YOUR ORG", project_label="YOUR PROJECT", data=embedding_payload)
als_vec_res_id = r['@id']
```
Then, we will create a resource of this data linking to the previously pushed file.
```
als_embedding_payload = {
    'modelName': 'ALS',
    'fileId': sgd_vec_file_id
}
r = nexus.resources.create(org_label="YOUR ORG", project_label="YOUR PROJECT", data=als_embedding_payload)
als_vec_res_id = r['@id']
```