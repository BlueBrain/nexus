@@@ index

* [Realms](iam-realms-api.md)
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

- permission: the value used to limit application's access to information.
- identity: the way to identify individuals.
- path: the URIs' path where to apply those access data restrictions.

An ACL defines the set of **permissions** that certain **identities** have on a concrete **path**.

@ref:[Operations on ACLs](iam-acls-api.md)