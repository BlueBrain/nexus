# Roadmap

## V1.1.x

This next release will be focused on ...

### Transparency in the view index processes

### Query across projects through Aggregate views (Beta)

### Expose service event logs through SSE

### Multiple file storage backends, S3 integration

### Resource graph visualization in Nexus Web

Leveraging the new capabilities of the API, the Nexus Web application will allow
users to visualize a resource as a graph, in addition to the current raw JSON-LD
representation. It will make it easier for users to examine a resource and
determine its structure at a glance.

### ACL listing in Nexus Web

Users with the right permissions will be able to see all of their ACLs
for any path in the system: global (root), organizations, projects.

### Linked data navigation in Nexus Web

Thanks to the SPARQL capabilities of the API, it is possible to find
resources that link to the current resource (incoming links) and
resources that the current resource links to (outgoing links). From
Nexus Web, users will be able to navigate between resources by following
incoming and outgoing links.

### Resolver management support in the JS and Python SDKs

One of the last pieces remaining to have full coverage of the API in the
SDKs was supporting the endpoints to manage resolvers. The SDKs will now be able
to create, update, tag, deprecate, fetch and list resolvers.

### Loading collections of schemas from the CLI (Beta)

### API Enhancements
