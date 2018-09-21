@@@ index

* [Organizations](admin-orgs-api.md)
* [Projects](admin-projects-api.md)

@@@

# Admin API

The Admin API provides operations on two types of resources, `organizations` and `projects`. Those resources define the tree-like structure or grouping of the platform

## Organizations 
The top level resource in the platform. It is used to group and categorize its sub-resources.

@ref:[Operations on organizations](admin-orgs-api.md)

## Projects

The 2nd level resource in the platform. It is used to group and categorize its sub-resources. Relevant roles of a projects are:

- Defining settings which can be used for operations on sub-resources. 
- Providing (by default) isolation from resources inside other projects. This isolation can be avoided by defining @ref:[resolvers](../kg/kg-resolvers-api.md)

@ref:[Operations on projects](admin-projects-api.md)