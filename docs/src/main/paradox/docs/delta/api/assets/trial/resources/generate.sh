curl -X PUT \
     -H "Content-Type: application/json" \
     "http://localhost:8080/trial/resources/myorg/myproj/" \
     -d \
'{
   "schema": "https://bbp.epfl.ch/nexus/schema/morphology"
   "resource": {
     "@context": [
       "https://neuroshapes.org",
       "https://bluebrain.github.io/nexus/contexts/metadata.json",
       {
         "@vocab": "https://bluebrain.github.io/nexus/vocabulary/"
       }
     ],
     "@id": "https://bbp.epfl.ch/nexus/data/morphology-001",
     "@type": "Morphology",
     "name": "Morphology 001"
   }
 }'