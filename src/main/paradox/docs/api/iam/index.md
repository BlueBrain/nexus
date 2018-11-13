@@@ index

* [Realms](iam-realms-api.md)
* [Authentication](authentication.md)
* [Permissions](iam-permissions-api.md)
* [ACLs](iam-acls-api.md)

@@@

# IAM API

The IAM API provides operations on three types of resources, `realms`, `permissions` and `acls`.

## Realms 
A realm provides with the necessary information to perform authentication against a certain [OIDC](https://en.wikipedia.org/wiki/OpenID_Connect) provider .  

@ref:[Operations on realms](iam-realms-api.md)

## Permissions 
A permission is the basic unit to provide a way to limit applications' access to sensitive information.  

@ref:[Operations on permisions](iam-permissions-api.md)

## ACLs

In order to restrict applications' access to data by placing restrictions on them, three parameters are important:

- permission: the value used to limit a client (user, group) access to resources.
- identity: a client identity reference, e.g. a certain user, a group, an anonymous user or someone who is authenticated to a certain realm.
- path: the location where to apply the restrictions

An ACL defines the set of **permissions** that certain **identities** have on a concrete **path**.

@ref:[Operations on ACLs](iam-acls-api.md)