# Projects

Projects belong to an `organization` and are rooted in the corresponding `/v1/projects/{org_label}` path.
The purposes of projects are:

- Group and categorize sub-resources.
- Define settings that apply for operations on all sub-resources. 
- Provide isolation from resources inside other projects. This behavior can be changed by defining @ref:[resolvers](../kg/kg-resolvers-api.md)

Access to resources in the system depends on the access control list set for them. A caller may need to prove its identity by means of an **access token** passed in the `Authorization` header (`Authorization: Bearer {token}`).
Please visit @ref:[Authentication](../iam/authentication.md) to learn more about retrieving access tokens.

@@@ note { .tip title="Authorization notes" }	

When  creating projects, the caller must have `projects/create` permissions on the current path of the project or the ancestor paths.

When  updating projects, the caller must have `projects/write` permissions on the current path of the project or the ancestor paths.

When  reading projects, the caller must have `projects/read` permissions on the current path of the project or the ancestor paths.

@@@

## Project payload

```
{
  "description": "{description}",
  "base": "{base}",
  "vocab": "{vocab}",
  "apiMappings": [
   {
      "prefix": "{prefix}",
      "namespace": "{namespace}"
    },
    ...
  ]
}
```

where...
 
- `{description}`: String - an optional description for this project.
- `{base}`: IRI - is going to be used as a [curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/) in the generation of the `@id` children resources. E.g.: Let base be `http://example.com/`. When a [resource is created](../kg/kg-resources-api.html#create-a-resource-using-post) and no `@id` is present in the payload, the platform will generate an @id which will look like `http://example.com/{UUID}`. This field is optional and will default to `{{base}}/v1/resources/{org_label}/{project_label}/_/`.
- `{vocab}`: IRI - is going to be used as a [curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/) prefix for all unqualified predicates in children resources. E.g. if the vocab is set to `https://schema.org/`, when a field a resource is created and a field `name` is present in the payload, it will be expanded to `http://schema.org/name` by the system during indexing and fetch operations. This field is optional and will default to `{{base}}/v1/vocabs/{org_label}/{project_label}/`.
- `{apiMappings}`: Json object - provides a convinient way to deal with URIs when performing operations on a sub-resource. This field is optional.

### API Mappings
The `apiMappings` Json object array maps each `prefix` to its `namespace` so that curies on children endpoints can be
used.

Having the following `apiMappings`:

```
{
  "apiMappings": [
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
- `{namespace}`: IRI - the right hand side of a [curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/). It has [certain constrains (irelative-ref)](https://tools.ietf.org/html/rfc3987#page-7).

Let's see an example:
 
 ```json
 {
   "apiMappings": [
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

...where  `{label}` is the user friendly name assigned to this project. The semantics of the `label` should be
consistent with the type of data provided by its sub-resources, since it'll be a part of the sub-resources' URI.

**Example**

Request
:   @@snip [project.sh](../assets/project.sh)

Payload
:   @@snip [project.json](../assets/project.json)

Response
:   @@snip [project-ref-new.json](../assets/project-ref-new.json)


## Update a project

This operation overrides the payload.

In order to ensure a client does not perform any changes to a project without having had seen the previous revision of
the project, the last revision needs to be passed as a query parameter.

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


## List projects

```
GET /v1/projects?from={from}&size={size}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`


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

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`


**Example**

Request
:   @@snip [project-list-org.sh](../assets/project-list-org.sh)

Response
:   @@snip [project-list.json](../assets/project-list.json)
