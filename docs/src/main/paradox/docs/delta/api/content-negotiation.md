# Content Negotiation

When performing a request against Nexus Delta, clients can specify the desired format of the response they would like to receive.
This is done through a mechanism called **Content Negotiation**. 
Nexus Delta uses the HTTP @link:[Accept](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Accept){ open=new } Header in order to provide Content Negotiation capabilities.

**Example**

```bash
curl -H "Accept: application/json" "http://localhost:8080/v1/version"
```

## Supported MIME types

Most of the Nexus Delta resources (except for querying the different indices and fetching files) support the following MIME types on the `Accept` Header:

- **application/ld+json**: JSON-LD output response. Further specifying the query parameter `format=compacted|expanded`
  will provide with the JSON-LD @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } or
  the @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **application/n-triples**: RDF n-triples response, as defined by the @link:[w3](https://www.w3.org/TR/n-triples/){ open=new }.
- **application/n-quads**: RDF n-quads response, as defined by the @link:[w3](https://www.w3.org/TR/n-quads/){ open=new }.
- **text/vnd.graphviz**: A @link:[DOT response](https://www.graphviz.org/doc/info/lang.html){ open=new }.

If `Accept: */*` HTTP header is present, Nexus defaults to the JSON-LD output in compacted form.