# Platform API

Nexus exposes a uniform consumer RESTful interface over HTTP(S).  The generally adopted transport format is JSON based,
specifically [JSON-LD](https://json-ld.org/).  The _Edge_ component routes requests to the appropriate services based
on the intent of the caller and a content negotiation scheme.

An additional interface for asynchronous communication is exposed over Kafka's protocol internally in the same format.

Individual service API refences:

* KnowledgeGraph: @extref[API Reference](service:kg/api-reference/index.html)
* IAM: @extref[API Reference](service:iam/api-reference/index.html)

## Error Signaling

Services make use of the HTTP Status Codes to report the outcome of each API call.  The status codes are complemented
by a consistent response data model for reporting client and system level failures.

Format
:   @@snip [error.json](../../resources/api/error.json)

Example
:   @@snip [error-example.json](../../resources/api/error-example.json)

While the format only specifies `code` and `message` fields, additional fields may be presented for additional
information in certain scenarios.