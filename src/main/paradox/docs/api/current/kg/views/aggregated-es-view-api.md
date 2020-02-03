# AggregateElasticSearchView

This view is an aggregate of ElasticSearchViews. The view itself does not create any index, but it references the already existing indices of the linked ElasticSearchViews.

When performing queries on the `_search` endpoint, this view will make use of the [multi-index](https://www.elastic.co/guide/en/elasticsearch/reference/current/multi-index.html) query capabilities of ElasticSearch in order to select the indices of every view present on this aggregate view.

If the caller does not have the permission `views/query` on all the projects defined on the aggregated view, only a subset of indices (or none) will be selected, respecting the defined permissions.

![Aggregate ElasticSearchView](../../assets/views/aggregate-view.png "Aggregate ElasticSearchView")

**AggregateElasticSearchView payload**

```
{
  "@id": "{someid}",
  "@type": "AggregateElasticSearchView",
  "views": [ 
    {
        "project": "{project}",
        "viewId": "{viewId}"
    },
    ...
  ]
}
```

where...
 
- `{project}`: String - The project, defined as `{org_label}/{project_label}`, where the `{viewId}` is located.
- `{viewId}`: Iri - The view @id value to be aggregated.
