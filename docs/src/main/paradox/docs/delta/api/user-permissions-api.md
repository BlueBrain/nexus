
# User Permissions

A user of delta is given certain permissions. This is done using the @ref:[ACLs API](acls-api.md) 

Sometimes for the sake of simplicity, it can be easier to ask whether the current user has a specific permission in a specific context. This is why the user permissions API exists

@@@ note { .warning }

The described endpoints are experimental and the responses structure might change in the future.

@@@

Requests
: All requests should have no body

Responses
: A response will have a 204 (no content) status code if the user is authorised
: A response will have a 403 (forbidden) status code if the user is not authorised


## Standard permissions

This operation determines whether the current logged in user has a specific permission in a specific context
```
HEAD /v1/user/permissions/{org_label}/{project_label}?permission={permission}
```
where
- `{permission}`: String - the permission to check


## Storage access permissions

This operation determines whether the current logged in user would be able to access files on a specific storage
```
HEAD /v1/user/permissions/{org_label}/{project_label}?storage={storage_id}&type={access_type}
```
where
- `{storage_id}`: String - the id of the storage
- `{access_type}`: String - the access type of the storage. Can be `read` or `write`

