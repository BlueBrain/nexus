# Admin

The Nexus Fusion admin app allows you to manage your Nexus Delta instance. In particular, you can:

- list, create, edit and deprecate organizations,
- list, create, edit and deprecate projects within an organization,
- check permissions on your projects and organizations,
- create, view, edit, and deprecate resources within your projects,
- query your Knowledge Graph with SPARQL and Elasticsearch views,
- create new Studios (or edit or deprecate them, as Studios are resources),
- check the indexing status of your projects.

> Note: Some organizations in Nexus Delta are created to support Nexus Fusion internals, such as the `webapps` organization. Be careful when deleting those.

## Organizations and Projects Indexes

When creating a project, you can use "Advanced Settings" to set Base, Vocab and API mapping for the project.

Base is an IRI used as a @link:[curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/){ open=new } for generating
'@id' of this child resources of the project.

Vocab is an IRI used as a @link:[curie](https://www.w3.org/TR/2010/NOTE-curie-20101216/){ open=new } prefix for all
unqualified predicates in child resources of the project. JSON object - provides a convenient way to deal with URIs
when performing operations on a sub-resource. This field is optional.

More info can be found @ref:[here](../delta/api/projects-api.md#api-mappings) regarding API Mappings.

### Creating a New Organization

@@@ div { .center }
![](../assets/try-nexus-sandbox-admin-create-org-form.png)
@@@

### Creating a New Project

@@@ div { .center }
![](../assets/try-nexus-sandbox-admin-organization.png)
@@@

@@@ div { .center }
![](../assets/try-nexus-sandbox-admin-create-project-form.png)
@@@

## Project View

The project view in your admin app is the center stage to manage a project.

@@@ div { .center }

![Project View](../assets/fusion-admin-project-page.png)

@@@

- On the left hand side, you will find the `Default Query`. You can filter and search resources, order them, as well
  as share that query with your peers. You can create as many queries as you like by clicking `+ Add another resource list`.
- On the right hand side, you find the `Project Tools`. You'll find the SPARQL and Elasticsearch query editors, and
  check the project permissions. You can also @ref:[create a new resource](admin.md#creating-a-new-resource) and upload files.
- "Manage studios for this project", will take you to the studio sub application. There you can create/edit/update
  studios for this project.

In the header, the breadcrumbs menu shows the organization and project your are in. You can also check the indexing
status of your project.

### Creating a New Resource

Navigate to the project where you want to create a resource. Click on the “Create Resource” button in the right sidebar.
Build the JSON payload with the help of the interactive editor, then hit “Save”.

## Resource View

In resource view, you can see various visual representations of a Nexus Resource. Based on the resource type, you will
see a set of plugins, each displayed on its own expandable section. By default, there will only be an admin plugin.
You can read more about admin plugin @ref:[here](plugins.md#default-plugins).

## Resource Descriptions

To enable users to visualize and describe their data, we have included a description editor and viewer. Resource descriptions are rendered first with a templating system, and then as markdown. This allows users to show off their resources using a combination of HTML and markdown that is useful to render resource properties, iterate through resource lists, or render images stored in Nexus Delta.

Nexus Fusion saves descriptions as a simple string inside the Resource's `description` property.

Markdown is rendered using [showdown](https://github.com/showdownjs/showdown), with templating provided by [handlebars](https://handlebarsjs.com/guide/).

This feature is also used in the @ref:[Studio Description](studio.md#studio-description).

@@@ note

The Handlebar templating will be resolved _before_ converting markdown.

@@@

### Example Description

#### Resource

```
{
  "@id": "9a4c53f4-ab50-459f-9aed-cd9ad57b9c5d",
  "@type": [
    "Entity",
    "Cell"
  ],
  "brainLocation": {
    "@type": "BrainLocation",
    "brainRegion": {
      "label": "primary somatosensory cortex"
    },
    "layer": {
      "label": "layer 4"
    }
  },
  "description": "# {{name}} | {{ objectOfStudy.label }}\n\n## Subject\nSpecies:  {{ subject.species.label }}\n<br/>\n![image](https://my-nexus-deployment.org/v1/files/my-org/my-project/f33e3bdc-7ca3-4dad-b100-88f4891d221c)\n\n## Brain Region\n{{ brainLocation.brainRegion.label }}",
  "name": "Cell #1029",
  "objectOfStudy": {
    "label": "Single Cell"
  },
  "subject": {
    "@type": "Subject",
    "age": {
      "period": "Post-natal",
      "unitCode": "days",
      "value": 13
    },
    "species": {
      "label": "Rattus norvegicus"
    }
  }
}
```

#### Description

```
# {{name}} | {{ objectOfStudy.label }}

## Subject
Species:  {{ subject.species.label }}
<br/>
![image](https://my-nexus-deployment.org/v1/files/my-org/my-project/f33e3bdc-7ca3-4dad-b100-88f4891d221c)

## Brain Region
{{ brainLocation.brainRegion.label }}

```

#### Result

![Description Editor Result](assets/description-editor-result.png)

### Editing a Resource Description

Users with write permissions on a Resource will have access to a tab called "Description". There, you can use a full WYSIWYG editor to type out and preview a description before saving it.

![Description Editor](assets/description-editor-example.png)
