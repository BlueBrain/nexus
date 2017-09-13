# API Basics

## HTTP Headers

## HTTP Status Codes
* **200 OK** - Request completed successfully.
* **201 Created** - Request created an item succesfully.
* **400 Bad Request** - Invalid JSON-LD payload.
* **405 Method Not Allowed** - Invalid HTTP verb for that resource.

## HTTP error and rejection Response JSON Object
| Field         | Type          | Description                                                       |
| ------------- |-------------  | ---------------------------------------------                     |
| type          | String        | Human readable name of the error.                                 |
| *             | *             | May contain some extra fields describing the error in more detail.|
