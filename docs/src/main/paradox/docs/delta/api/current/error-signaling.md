# Error Signaling

Nexus Delta makes use of the HTTP status codes to report the outcome of each API call. The status codes are
complemented by a consistent response data model for reporting client and system level failures.

Format
:   @@snip [error.json](assets/error.json)

Example
:   @@snip [error-example.json](assets/error-example.json)

In addition to mandatory `@type` and `reason` fields, arbitrary fields may be present for extra information in certain scenarios.