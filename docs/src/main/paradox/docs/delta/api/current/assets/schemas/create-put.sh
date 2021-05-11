curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/v1/schemas/myorg/myproj/e1729302-35b8-4d80-97b2-d63c984e2b5c" \
     -d \
'{
  "@context":  {
      "this": "http://localhost:8080/v1/schemas/myorg/myproj/e1729302-35b8-4d80-97b2-d63c984e2b5c/shapes",
      "ex": "http://localhot:9999/"
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