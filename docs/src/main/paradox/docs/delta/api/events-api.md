# Global events 

Nexus provides a global events endpoint, which allows the users to access the stream of events for all the resources in Nexus,
including ACLs, permissions, realms, etc.


To be able to access the endpoint, the user needs to have `events/read` permission on `/`.


## Check permissions

```
HEAD /v1/events
```
This endpoint allows checking whether the user has permission to read the events without starting the events stream.
The response will be either `200 OK` if the user does have `events/read` permission on `/` or `403 Forbidden` otherwise.

## Server Sent Events

```
GET /v1/events
```

The server sent events response contains a series of events, represented in the following way

```
data:{payload}
event:{type}
id:{id}
```

where...

- `{payload}`: Json - is the actual payload of the current event
- `{type}`: String - is a type identifier for the current event. Possible types are related to core resource types (Resouce, Schema, Resolver) and available plugin types
- `{id}`: String - is the identifier of the resource event. It can be used in the `Last-Event-Id` query parameter


**Example**

Request
:   @@snip [sse.sh](assets/events/sse.sh)

Response
:   @@snip [sse.json](assets/events/sse.json)




