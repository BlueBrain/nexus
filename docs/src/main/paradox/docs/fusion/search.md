# Search

The Search SubApp enables users to search or filter data stored in Nexus Delta in various projects in a way that is configurable. It's intended as a global search tool for your Nexus instance.

The administrator who configures one or more `SearchConfigs` for their Search SubApp has power over what data is searchable, and how it will be presented in the Search interface.

@@@ note

### Powered by Elastic Search Views

This feature leverages the Elastic Search indexing capability of Nexus Delta. It uses the `SearchConfig` to decide how to query an @ref:[Elastic Search View](../delta/api/current/views/elasticsearch-view-api.md). Because it depends on the @ref:[Elastic Search View](../delta/api/current/views/elasticsearch-view-api.md) feature, make sure that your `mappings` property is properly configured to index the data in the way you expect. An incorrectly configured `mapping` property might not show the appropriate `Facets` you might expect, or it could simple result in nothing shown.

We recommend using an @ref:[Aggregate Search View](../delta/api/current/views/aggregated-es-view-api.md), so that you can query multiple projects at once. However, this feature is limited to 10 to 15 projects, depnding on their index name length.

@@@

## Search Config

### Where are SearchConfigs stored?

Search configs are saved in a project as a simple @ref:[Resource](../delta/api/current/kg-resources-api.md), with a type `nxv:SearchConfig`.

Nexus Fusion must be made aware of which project to look for these resources, either by using the env var `SEARCH_CONFIG_PROJECT`

```bash
  SEARCH_CONFIG_PROJECT=my-org/my-project
```

or Nexus Fusion will use the default `SEARCH_CONFIG_PROJECT` value, `webapps/nexus-web`

### Example SearchConfig

```
{
  "@type": [
    "nxv:SearchConfig"
  ],
  "description": "global dataset search",
  "facets": [
    {
      "key": "brainLocationLabel",
      "label": "Brain Region",
      "propertyKey": "brainLocation.brainRegion.label.raw",
      "type": "terms"
    },
    {
      "key": "objectOfStudyLabel",
      "label": "Object of Study",
      "propertyKey": "objectOfStudy.label.raw",
      "type": "terms"
    },
    {
      "key": "type",
      "label": "Type",
      "propertyKey": "@type",
      "type": "terms"
    }
  ],
  "fields": [
    {
      "dataIndex": "label",
      "displayIndex": 0,
      "key": "label",
      "title": "Label"
    },
    {
      "dataIndex": "@type",
      "displayIndex": 3,
      "key": "@type",
      "sortable": true,
      "title": "Type"
    },
    {
      "dataIndex": "objectOfStudy.label",
      "displayIndex": 2,
      "key": "objectOfStudyLabel",
      "title": "Object of Study"
    },
    {
      "dataIndex": "brainLocation.brainRegion.label",
      "displayIndex": 1,
      "key": "brain-region",
      "title": "Brain Region"
    }
  ],
  "label": "MINDS",
  "view": "https://my-nexus-deployment.com/v1/views/webapps/nexus-web/my-view-id"
}
```

### Facets

#### Defaults

### Fields

#### Defaults

## Search Bar

### Elastic Search Mapping dependency
