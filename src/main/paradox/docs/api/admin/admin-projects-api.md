# Projects

Projects are rooted in the `/v1/projects/{org_label}` and are used to group and categorize its sub-resources. Relevant roles of a projects are:

- Defining settings which can be used for operations on sub-resources. 
- Providing (by default) isolation from resources inside other projects. This isolation can be avoided by defining @ref:[resolvers](../kg/kg-resolvers-api.md)

Each project... 

- belongs to an `organization` identifier by the label `{org_label}` 
- it is validated against the [project schema](https://bluebrain.github.io/nexus/schemas/project).

Any resources in the system might be protected using an **access token**, provided by the HTTP header `Authorization: Bearer {access_token}`. Visit @ref:[Authentication](../iam/iam-realms-api.md) in order to learn more about how to retrieve an access token.

@@@ note { .tip title="Running examples with Postman" }

The simplest way to explore our API is using [Postman](https://www.getpostman.com/apps). Once downloaded, import the [projects collection](../assets/project-postman.json).

If your deployment is protected by an access token: 

Edit the imported collection -> Click on the `Authorization` tab -> Fill the token field.

@@@

## Projects payload

```
{
  "name": "{name}",
  "base": "{base}",
  "prefixMappings": [
   {
      "prefix": "{prefix}",
      "namespace": "{namespace}"
    },
    ...
  ]
}
```

where...
 
- `{name}`: String - the name for this project.
- `{base}`: Iri - is going to be used as a [curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/) in the generation of the `@id` children resources. E.g.: Let base be `http://example.com/`. When a [resource is created using POST](../kg/kg-resources-api.html#create-a-resource-using-post) and no `@id` is present on the payload, the platform will generate and @id which will look like `http://example.com/{UUID}`. This field is optional and it will default to `{{base}}/v1/{org_label}/{project_label}`.
- `{prefixMappings}`: Json object - provides a convinient way to deal with URIs when performing operations on a sub-resource. This field is optional.

### Prefix Mappings
The `prefixMappings` Json object array maps each `prefix` to its `namespace` so that curies on children endpoints can be used. Let's see an example.

Having the following `prefixMappings`:

```
{
  "prefixMappings": [
   {
      "prefix": "{prefix}",
      "namespace": "{namespace}"
    },
    { ... }
  ]
}
```

where...

- `{prefix}`: String - the left hand side of a [curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/). It has [certain constrains](https://www.w3.org/TR/1999/REC-xml-names-19990114/#NT-NCName).
- `{namespace}`: Iri - the right hand side of a [curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/). It has [certain constrains (irelative-ref)](https://tools.ietf.org/html/rfc3987#page-7).

The `prefixMappings` Json object array maps each `prefix` to its `namespace` so that curies on children endpoints can be used. Let's see an example:
 
 ```json
 {
   "prefixMappings": [
    {
       "prefix": "person",
       "namespace": "http://example.com/some/person"
     },
     {
       "prefix": "schemas",
       "namespace": "https://bluebrain.github.io/nexus/schemas/"
     }
   ]
 }
 ```

The previous payload allows us to [create a schema](../kg/kg-schemas-api.html##create-a-schema-using-put) using the following endpoints:

- `/v1/schemas/{org_label}/{project_label}/person`. The `@id` of the resulting schema will be `http://example.com/some/person`
- `/v1/schemas/{org_label}/{project_label}/schema:other`. The `@id` of the resulting schema will be `https://bluebrain.github.io/nexus/schemas/other`

## Create a project

```
PUT /v1/projects/{org_label}/{label}
  {...}
```

...where  `{label}` is the user friendly name assigned to this project. The semantics of the `label` should be consistent with the type of data provided by its sub-resources, since they are exposed on the URI of the sub-resource's operations.

**Example**

Request
:   @@snip [project.sh](../assets/project.sh)

Payload
:   @@snip [project.json](../assets/project.json)

Response
:   @@snip [project-ref-new.json](../assets/project-ref-new.json)


## Update a project

This operation overrides the payload.

In order to ensure a client does not perform any changes to a project without having had seen the previous revision of the project, the last revision needs to be passed as a query parameter.

```
PUT /v1/projects/{org_label}/{label}?rev={previous_rev}
  {...}
```
... where 

- `{previous_rev}`: Number - the last known revision for the organization.
- `{label}`: String - the user friendly name that identifies this project.

**Example**

Request
:   @@snip [project-update.sh](../assets/project-update.sh)

Payload
:   @@snip [project.json](../assets/project.json)

Response
:   @@snip [project-ref-updated.json](../assets/project-ref-updated.json)


## Tag a project

Links a project revision to a specific name. 

Tagging a project is considered to be an update as well.

```
PUT /v1/projects/{org_label}/{label}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{name}`: String -  label given the project at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.
- `{previous_rev}`: Number - the last known revision for the organization.
- `{label}`: String - the user friendly name that identifies this project.

**Example**

Request
:   @@snip [project-tag.sh](../assets/project-tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [project-ref-tagged.json](../assets/project-ref-tagged.json)


## Deprecate a project

Locks the project, so no further operations can be performed on it or on the children resources.

Deprecating a project is considered to be an update as well. 

```
DELETE /v1/projects/{org_label}/{label}?rev={previous_rev}
```

... where 

- `{previous_rev}`: Number - the last known revision for the organization.
- `{label}`: String - the user friendly name that identifies this project.

**Example**

Request
:   @@snip [project-deprecate.sh](../assets/project-deprecate.sh)

Response
:   @@snip [project-ref-deprecated.json](../assets/project-ref-deprecated.json)


## Fetch a project (current version)

```
GET /v1/projects/{org_label}/{label}
```

...where `{label}` is the user friendly name that identifies this project.


**Example**

Request
:   @@snip [project-fetch.sh](../assets/project-fetch.sh)

Response
:   @@snip [project-fetched.json](../assets/project-fetched.json)


## Fetch a project (specific version)

```
GET /v1/projects/{org_label}/{label}?rev={rev}
```

...where

- `{label}`: String - the user friendly name that identifies this project.
- `{rev}`: Number - the revision of the project to be retrieved.

**Example**

Request
:   @@snip [project-fetch-revision.sh](../assets/project-fetch-revision.sh)

Response
:   @@snip [project-fetched.json](../assets/project-fetched.json)


## Fetch a project (specific tag)

```
GET /v1/projects/{org_label}/{label}?tag={tag}
```

... where 

- `{label}`: String - the user friendly name that identifies this project.
- `{tag}`: String - the tag of the project to be retrieved.

**Example**

Request
:   @@snip [project-fetch-tag.sh](../assets/project-fetch-tag.sh)

Response
:   @@snip [project-fetched-tag.json](../assets/project-fetched-tag.json)


## Get the ACLs for a project

```
GET /v1/projects/{org_label}/{label}/acls?self=true&parents={parents}
```
... where 

- `{label}`: String - the user friendly name that identifies this project.
- `{parents}`: Boolean - if true, the response includes the ACLs for the parent `organization` of the current project. If false, the response only includes the project ACLs.

**Example**

Request
:   @@snip [project-fetch-acls.sh](../assets/project-fetch-acls.sh)

Response
:   @@snip [project-acls-fetched.json](../assets/project-acls-fetched.json)


## List projects

```
GET /v1/projects?from={from}&size={size}&deprecated={deprecated}&q={full_text_search_query}
```

where...

- `{full_text_search_query}`: String - can be provided to select only the projects in the collection that have attribute values matching (containing) the provided token; when this field is provided the results will also include score values for each result
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting projects based on their deprecation status


**Example**

Request
:   @@snip [project-list.sh](../assets/project-list.sh)

Response
:   @@snip [project-list.json](../assets/project-list.json)


## List projects belonging to an organization

```
GET /v1/projects/{org_label}?from={from}&size={size}&deprecated={deprecated}&q={full_text_search_query}
```

where...

- `{full_text_search_query}`: String - can be provided to select only the projects in the collection that have attribute values matching (containing) the provided token; when this field is provided the results will also include score values for each result
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting projects based on their deprecation status


**Example**

Request
:   @@snip [project-list-org.sh](../assets/project-list-org.sh)

Response
:   @@snip [project-list.json](../assets/project-list.json)