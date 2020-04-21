package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.{CirceEq, RdfSpec}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.ContextSpec

class JsonLDSpec extends RdfSpec with JsonLdFixtures with CirceEq {
  val excluded = Set(
    "0005-in.jsonld", // @id with value #me
    "0028-in.jsonld", // relative @id is built using the base uri of the domain. Not supported yet
    "0029-in.jsonld", // relative @id is built using the base uri of the domain. Not supported yet
    "0040-in.jsonld", // relative @id is built using the base uri of the domain. Not supported yet
    "0048-in.jsonld", // relative @id not supported yet
    "0050-in.jsonld", // create absolute @id from base uri not supported
    "0056-in.jsonld", // relative @id not supported yet
    "0057-in.jsonld", // relative @id not supported yet
    "0059-in.jsonld", // relative @id not supported yet
    "0060-in.jsonld", // relative @id not supported yet
    "0066-in.jsonld", // relative @id not supported yet
    "0068-in.jsonld", // blank node @id not supported yet
    "0076-in.jsonld", // blank node @id not supported yet
    "0077-in.jsonld", // ignore this test, the context is provided outside the scope of the file
    "0051-in.jsonld", // absolute @id  with (/) not supported yet
    "0061-in.jsonld", // double quotes on value objects when not necessary
    "0078-in.jsonld", // relative @id not supported yet
    "0110-in.jsonld", // relative uris are resolved
    "0111-in.jsonld", // relative uris are resolved
    "0112-in.jsonld", // relative uris are resolved
    "0122-in.jsonld", // ignore invalid term when expanding iri. TO BE FIXED
    "c005-in.jsonld", // it works, but the jsonLD comparison is wrong
    "c011-in.jsonld", // it works, but Urn in tests is wrong
    "c013-in.jsonld", // it works, but Urn in tests is wrong
    "m001-in.jsonld", // @id with blank node not supported
    "m002-in.jsonld", // @id with blank node not supported
    "m003-in.jsonld", // @id with blank node not supported
    "m004-in.jsonld", // @id with blank node not supported
    "m004-in.jsonld", // @id with blank node not supported
    "m005-in.jsonld", // @id without @base not supported
    "pi06-in.jsonld", // property-based index maps not supported yet
    "pi07-in.jsonld", // property-based index maps not supported yet
    "pi08-in.jsonld", // property-based index maps not supported yet
    "pi09-in.jsonld", // property-based index maps not supported yet
    "pi10-in.jsonld", // property-based index maps not supported yet
    "pi11-in.jsonld"  // property-based index maps not supported yet
  ) ++ ContextSpec.excluded

  "A JsonLD" should {
    "be expanded" in {

      forAll(expandTestCases) {
        case ((inName, json), (_, Some(outJson)))
            if !excluded.contains(inName) && !inName.startsWith("js") && !inName.startsWith("pr") =>
          val expanded     = JsonLD.expand(json).rightValue
          val expandedJson = expanded.toJson()
          println(s""""/jsonld/expand/$inName",""")
          expandedJson should equalIgnoreArrayOrder(outJson)
        case _ => // ignore
      }
    }
  }
}
