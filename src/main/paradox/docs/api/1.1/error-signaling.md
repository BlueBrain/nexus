# Error Signaling

The services makes use of the HTTP Status Codes to report the outcome of each API call.  The status codes are
complemented by a consistent response data model for reporting client and system level failures.

Format
:   @@snip [error.json](assets/error.json)

Example
:   @@snip [error-example.json](assets/error-example.json)

While the format only specifies `_code` and `_message` fields, additional fields may be presented for additional information in certain scenarios.