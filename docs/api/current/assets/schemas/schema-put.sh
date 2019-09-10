curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/schemas/myorg/myproj/base:e1729302-35b8-4d80-97b2-d63c984e2b5c" -d \
'{
  "@context":  {
      "this": "https://nexus.example.com/v1/schemas/myorg/myproj/e1729302-35b8-4d80-97b2-d63c984e2b5c/shapes",
      "ex": "http://example.com/"
  },
  "shapes": [
    {
      "@id": "this:MyShape",
      "@type": "sh:NodeShape",
      "nodeKind": "sh:BlankNodeOrIRI",
      "targetClass": "ex:Custom",
      "property": [
        {
          "path": "ex:name",
          "datatype": "xsd:string",
          "minCount": 1
        },
        {
          "path": "ex:number",
          "datatype": "xsd:integer",
          "minCount": 1
        },
        {
          "path": "ex:bool",
          "datatype": "xsd:boolean",
          "minCount": 1
        }
      ]
    }
  ]
}'