# Practice
Practice operations contain read-only operations designed to help users compose and validate their
resources before effectively saving them in Nexus.

@@@ note { .tip title="Authorization notes" }

When performing a request, the caller must have `resources/read` permission on the project each resource belongs to.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Resource generation

This endpoint allows to create and get the output of a resource, optionally validating with an
existing schema or a new one.

It applies the same validation steps than the creation/update of resources, the main difference being 
that nothing is persisted.

```
GET /v1/practice/resources/{org_label}/{project_label}

{
  "schema": {schema},
  "resource": {resource}
}
```

Where:

* `{schema}`: String/Json: The schema to validate the provided resource. If a string is provided, it will attempt to resolve it as an existing schema.
If a json payload is provided, it will attempt to generate the schema and then use the result to validate the resource.
This field is optional and defaults to no SHACL validation.
* `{resource}`: Json: The resource payload to test and validate

The Json response will contain:

* The generated resource in the compacted JSON-LD format if the generation and the validation was successful
* The generated schema if a new schema payload was provided
* The error if the one of the steps fails (invalid resource/invalid new schema/existing schema not found/...)

**Example**

Request
:   @@snip [create.sh](assets/practice/resources/generate.sh)

Payload
:   @@snip [payload.json](assets/practice/resources/payload.json)

Response
:   @@snip [created.json](assets/practice/resources/generated.json)

## Validate

This operation runs validation of a resource against a schema. This would be useful to test whether resources would
match the shape of a new schema.

```
GET /v1/resources/{org_label}/{project_label}/{schema_id}/{resource_id}/validate
```

**Example**

Request
:   @@snip [validate.sh](assets/resources/validate.sh)

Response
:   @@snip [validated.json](assets/resources/validated.json)