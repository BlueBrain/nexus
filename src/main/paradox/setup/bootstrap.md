# Bootstrapping KG data

Once all the services are running, you can create some organizations, domains, schemas and instances.

Before using the KG service the triple store indices need to be created.  Please download each of the index property
files locally and run the commands below (adjusting the address to the blazegraph deployment):

   * [organizations](resources/index-organizations.properties)
   * [domains](resources/index-domains.properties)
   * [schemas](resources/index-schemas.properties)
   * [instances](resources/index-instances.properties)
   
```bash
curl -v -H "Content-Type: text/plain; charset=UTF-8" -X POST "http://localhost:8889/bigdata/namespace" --data-binary '@index-organizations.properties'
curl -v -H "Content-Type: text/plain; charset=UTF-8" -X POST "http://localhost:8889/bigdata/namespace" --data-binary '@index-domains.properties'
curl -v -H "Content-Type: text/plain; charset=UTF-8" -X POST "http://localhost:8889/bigdata/namespace" --data-binary '@index-schemas.properties'
curl -v -H "Content-Type: text/plain; charset=UTF-8" -X POST "http://localhost:8889/bigdata/namespace" --data-binary '@index-instances.properties'
```

Download an example of a [schema](resources/schema.json) and an [instance](resources/instance.json) in your current
directory. Afterwards, execute the following calls:

```bash
# Creating organizaion
curl -v -H "Content-Type: application/json" -X PUT "http://localhost:8080/v0/organizations/nexus" -d '{"description": "Nexus Organization"}'

# Creating domain
curl -v -H "Content-Type: application/json" -X PUT "http://localhost:8080/v0/organizations/nexus/domains/core" -d '{"description": "Nexus Core domain"}'

# Creating schema
curl -v -H "Content-Type: application/json" -X PUT "http://localhost:8080/v0/schemas/bbp/core/entity/v1.0.0" --data-binary '@schema.js'

# Publishing schema
curl -v -H "Content-Type: application/json" -X PATCH "http://localhost:8080/v0/schemas/bbp/core/entity/v1.0.0/config?rev=1" -d '{"published": true}'

# Creating instance
curl -v -H "Content-Type: application/json" -X POST "http://localhost:8080/v0/data/bbp/core/entity/v1.0.0" --data-binary'@instance.js'

# Add attachment to instance
curl -v -X PUT -F "file=@/path/to/attachment" "http://localhost:8080/v0/data/bbp/core/entity/v1.0.0/${instance_id}/attachment?rev=1"

```