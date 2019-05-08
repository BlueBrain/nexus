curl -XPUT -H "Content-Type: application/json" "https://nexus.example.com/v1/views/myorg/myproj3/nxv:myagg" -d \
'{
  "@context": {
    "nxv": "https://bluebrain.github.io/nexus/vocabulary/"
  },
  "@type": [
    "AggregateElasticSearchView",
  ],
  "views": [
    {
      "project": "myorg/myproj",
      "viewId": "nxv:myview"
    },
    {
      "project": "myorg/myproj2",
      "viewId": "nxv:myview2"
    }
  ]
}'