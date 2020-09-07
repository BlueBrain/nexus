# Projects

Projects belong to an `organization` and are rooted in the corresponding `/v1/projects/{org_label}` path.
The purposes of projects are:

- Group and categorize sub-resources.
- Define settings that apply for operations on all sub-resources. 
- Provide isolation from resources inside other projects. This behavior can be changed by defining 
  @ref:[resolvers](kg-resolvers-api.md)

Access to resources in the system depends on the access control list set for them. A caller may need to prove its 
identity by means of an **access token** passed in the `Authorization` header (`Authorization: Bearer {token}`).
Please visit @ref:[Authentication](authentication.md) to learn more about retrieving access tokens.

@@@ note { .tip title="Authorization notes" }	

When  creating projects, the caller must have `projects/create` permissions on the current path of the project or the 
ancestor paths.

When  updating projects, the caller must have `projects/write` permissions on the current path of the project or the 
ancestor paths.

When  reading projects, the caller must have `projects/read` permissions on the current path of the project or the 
ancestor paths.

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
- `{base}`: IRI - is going to be used as a @link:[curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/){ open=new } 
  in the generation of the `@id` children resources. E.g.: Let base be `http://example.com/`. When a 
  @ref:[resource is created](kg-resources-api.md#create-a-resource-using-post) and no `@id` is present in the 
  payload, the platform will generate an @id which will look like `http://example.com/{UUID}`. This field is optional 
  and will default to `{{base}}/v1/resources/{org_label}/{project_label}/_/`.
- `{vocab}`: IRI - is going to be used as a @link:[curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/){ open=new } 
  prefix for all unqualified predicates in children resources. E.g. if the vocab is set to `https://schema.org/`, when 
  a field a resource is created and a field `name` is present in the payload, it will be expanded to 
  `http://schema.org/name` by the system during indexing and fetch operations. This field is optional and will default 
  to `{{base}}/v1/vocabs/{org_label}/{project_label}/`.
- `{apiMappings}`: Json object - provides a convinient way to deal with URIs when performing operations on a 
  sub-resource. This field is optional.

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

- `{prefix}`: String - the left hand side of a @link:[curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/){ open=new }. 
  It has @link:[certain constraints](https://www.w3.org/TR/1999/REC-xml-names-19990114/#NT-NCName){ open=new }.
- `{namespace}`: IRI - the right hand side of a @link:[curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/){ open=new }. 
  It has @link:[certain constraints (irelative-ref)](https://tools.ietf.org/html/rfc3987#page-7){ open=new }.

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
       "namespace": "https://bluebrainnexus.io/schemas/"
     }
   ]
 }
 ```

The previous payload allows us to @ref:[create a schema](kg-schemas-api.md#create-a-schema-using-put) using the 
following endpoints:

- `/v1/schemas/{org_label}/{project_label}/person`. The `@id` of the resulting schema will be `http://example.com/some/person`
- `/v1/schemas/{org_label}/{project_label}/schema:other`. The `@id` of the resulting schema will be `https://bluebrainnexus.io/schemas/other`

## Create a project

```
PUT /v1/projects/{org_label}/{label}
  {...}
```

...where  `{label}` is the user friendly name assigned to this project. The semantics of the `label` should be
consistent with the type of data provided by its sub-resources, since it'll be a part of the sub-resources' URI.

**Example**

Request
:   @@snip [project.sh](assets/project.sh)

Payload
:   @@snip [project.json](assets/project.json)

Response
:   @@snip [project-ref-new.json](assets/project-ref-new.json)


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
:   @@snip [project-update.sh](assets/project-update.sh)

Payload
:   @@snip [project.json](assets/project.json)

Response
:   @@snip [project-ref-updated.json](assets/project-ref-updated.json)


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
:   @@snip [project-deprecate.sh](assets/project-deprecate.sh)

Response
:   @@snip [project-ref-deprecated.json](assets/project-ref-deprecated.json)


## Fetch a project (current version)

```
GET /v1/projects/{org_label}/{label}
```

...where `{label}` is the user friendly name that identifies this project.


**Example**

Request
:   @@snip [project-fetch.sh](assets/project-fetch.sh)

Response
:   @@snip [project-fetched.json](assets/project-fetched.json)


## Fetch a project (specific version)

```
GET /v1/projects/{org_label}/{label}?rev={rev}
```

...where

- `{label}`: String - the user friendly name that identifies this project.
- `{rev}`: Number - the revision of the project to be retrieved.

**Example**

Request
:   @@snip [project-fetch-revision.sh](assets/project-fetch-revision.sh)

Response
:   @@snip [project-fetched.json](assets/project-fetched.json)


## List projects

```
GET /v1/projects?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&label=label
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting projects based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting projects based on their revision value
- `{type}`: Iri - can be used to filter the resulting projects based on their `@type` value. This parameter can 
  appear multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting projects based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting projects based on the person which performed the last update
- `{label}`: String - can be used to filter the resulting projects based on its label. E.g.: `label=my` will match any 
  project's label that contains the string `my`. `label='my'` will match any project where label is equal to `my`. 


**Example**

Request
:   @@snip [project-list.sh](assets/project-list.sh)

Response
:   @@snip [project-list.json](assets/project-list.json)


## List projects belonging to an organization

```
GET /v1/projects/{org_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&label=label
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting projects based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting projects based on their revision value
- `{type}`: Iri - can be used to filter the resulting projects based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting projects based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting projects based on the person which performed the last update
- `{label}`: String - can be used to filter the resulting projects based on its label. E.g.: `label=my` will match any 
  project's label that contains the string `my`.


**Example**

Request
:   @@snip [project-list-org.sh](assets/project-list-org.sh)

Response
:   @@snip [project-list.json](assets/project-list.json)


## Project Server Sent Events

This endpoint allows clients to receive automatic updates from the projects in a streaming fashion.

```
GET /v1/projects/events
```

where `Last-Event-Id` is an optional HTTP Header that identifies the last consumed project event. It can be used for 
cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

The response contains a series of project events, represented in the following way

```
data:{payload}
event:{type}
id:{id}
```

where...

- `{payload}`: Json - is the actual payload of the current project
- `{type}`: String - is a type identifier for the current project. Possible types are: ProjectCreated, ProjectUpdated 
  and ProjectDeprecated
- `{id}`: String - is the identifier of the project event. It can be used in the `Last-Event-Id` HTTP Header

**Example**

Request
:   @@snip [project-event.sh](assets/project-event.sh)

Response
:   @@snip [project-event.json](assets/project-event.json)
