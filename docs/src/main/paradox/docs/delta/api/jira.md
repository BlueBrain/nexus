# Jira integration

The Jira integration plugin comes from the need at BBP to have the Nexus platform and Jira to interact by linking Nexus resources 
and Jira issues.

The @link:[Jira server](https://developer.atlassian.com/server/jira/platform/getting-started/) API only allows to authenticate 
clients using the OAuth 1.0a protocol. This implies some set-up on the server-side so a minimal plugin was introduced in Delta to handle 
the authorization flow and to expose the Jira API operations needed by @ref[Nexus fusion](../../fusion/index.md) to link Nexus resources 
and Jira issues.

A guide introducing OAuth and how to integrate with Jira using it with more details is  available @link[on the Atlassian website](https://developer.atlassian.com/server/jira/platform/oauth/)

@@@ note { .tip title="Authorization notes" }

Like the other endpoints in Delta, clients still have to present a valid token.

To have access to the different endpoints provided by this plugin, the user must first grant Delta to access to their information and to act on their
behalf as defined by the authorization workflow presented @link:[here](https://developer.atlassian.com/server/jira/platform/oauth/#authorization-flow).

As Nexus impersonates the user when interacting with Jira, it will do it with the same permissions as the user.
Nexus does not perform any additional permission checks, it relies fully on Jira to perform this task.

@@@

## Request token

Starts the authorization process by obtaining a request token from Jira which will return a link so that the user can grant permission to Nexus to 
access Jira on their behalf.

Nexus passes along the link to the client.

```
POST /v1/jira/request-token
```

**Example**

Request
:   @@snip [request-token.sh](assets/jira/request-token.sh)

Response
:   @@snip [request-token-response.json](assets/jira/request-token-response.json)

## Access token

After granting access to Delta, the user will get an access code that must be passed to this endpoint so that Delta can finalize the authorization flow
and get an access token so that it can request and receive data from Jira.

```
POST /v1/jira/access-token
```

**Example**

Request
:   @@snip [access-token.sh](assets/jira/access-token.sh)

An empty resource is returned for this endpoint.

## Create an issue

```
POST /v1/jira/issue
```

The request and response are the same as those described for the @link:[create issue endpoint](https://docs.atlassian.com/software/jira/docs/api/REST/8.22.2/#issue-createIssue) 
in the Jira Reference except for the `updateHistory` parameter which is not available in the Nexus endpoint.

## Edit an issue

```
PUT /v1/jira/issue/{issueId}
```

The request and response are the same as those described  for the @link:[edit issue endpoint](https://docs.atlassian.com/software/jira/docs/api/REST/8.22.2/#issue-editIssue) 
in the Jira Reference except for the `notifyUsers` parameter which is not available in the Nexus endpoint

## List available projects

```
GET /v1/jira/project?recent={number}
```

The request and response are the same as those described for the @link:[list projects endpoint](https://docs.atlassian.com/software/jira/docs/api/REST/8.22.2/#project-getAllProjects) 
in the Jira Reference except for the `expand`, `includeArchived` and `browseArchive` parameters which are not available in the Nexus endpoint.

## Search issues

```
POST /v1/jira/search
```

The request and response are the same as those described @link:[search endpoint](https://docs.atlassian.com/software/jira/docs/api/REST/8.22.2/#search-searchUsingSearchRequest) in the Jira Reference.