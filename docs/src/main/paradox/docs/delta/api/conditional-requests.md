# Conditional requests

Nexus Delta supports conditional requests as defined @link:[here](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-p4-conditional-26) 
for the different operations:
* Fetch operation for the different types of resource
* Fetch original payloads for the different types of resources
* Fetching the file contents

The response for those operations are augmented with respective `ETag` and `Last-Modified` response headers.

The client can then use those values to set up caches and save bandwidth by using the 
@link[conditional headers](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-p4-conditional-26#section-3)
as Delta can immediately answer with a `304 Not Modified` and not resend the full response. 


