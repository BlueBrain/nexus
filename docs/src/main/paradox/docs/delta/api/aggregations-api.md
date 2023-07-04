# Aggregations

The Aggregations API allows to aggregate resources by predefined terms. Currently, the following aggregations will be
returned:

* `projects`: a bucket aggregation of the resources by the project they belong to
* `types`: a bucket aggregation of the `@types` featured in the resources

## Aggregate

### Within a project

```
GET /v1/aggregations/{org_label}/{project_label}
```

### Within an organization

This operation only aggregates resources from projects defined in the organisation `{org_label}` where the caller has
the `resources/read` permission.

```
GET /v1/aggregations/{org_label}
```

### Within all projects

This operation only aggregates resources from projects where the caller has the `resources/read` permission.

```
GET /v1/aggregations
```

**Example**

Request
:   @@snip [aggregate.sh](assets/resources/aggregate.sh)

Response
:   @@snip [aggregated.json](assets/resources/aggregated.json)