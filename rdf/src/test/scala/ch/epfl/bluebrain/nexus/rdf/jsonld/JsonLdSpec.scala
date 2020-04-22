package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.{CirceEq, RdfSpec}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.ContextSpec

class JsonLdSpec extends RdfSpec with JsonLdFixtures with CirceEq {
  val excluded = Set(
    "0005-in.jsonld", // @id with value #me
    "0060-in.jsonld", // relative unexpanded @id and @type not supported
    "0068-in.jsonld", // @id with blank node not supported
    "0076-in.jsonld", // @id with blank node not supported
    "0077-in.jsonld", // ignore this test, the context is provided outside the scope of the file
    "0061-in.jsonld", // double quotes on value objects when not necessary
    "0091-in.jsonld", // merging of contexts with @base absolute and relative not supported yet
    "0092-in.jsonld", // relative uris are resolved. That's a feature of our Uri implementation, not a bug
    "0110-in.jsonld", // relative uris are resolved. That's a feature of our Uri implementation, not a bug
    "0111-in.jsonld", // relative uris are resolved. That's a feature of our Uri implementation, not a bug
    "0112-in.jsonld", // relative uris are resolved. That's a feature of our Uri implementation, not a bug
    "0122-in.jsonld", // ignore invalid term when expanding iri. TO BE FIXED
    "c005-in.jsonld", // it works, but the jsonLD comparison is wrong
    "c011-in.jsonld", // it works, but Urn in tests is wrong
    "c013-in.jsonld", // it works, but Urn in tests is wrong
    "m001-in.jsonld", // @id with blank node not supported
    "m002-in.jsonld", // @id with blank node not supported
    "m003-in.jsonld", // @id with blank node not supported
    "m004-in.jsonld", // @id with blank node not supported
    "m004-in.jsonld", // @id with blank node not supported
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
        case ((inName, json), (_, Some(outJson)), options) if !excluded.contains(inName) && !inName.startsWith("pr") =>
          println(inName)
          implicit val opt: JsonLdOptions = options
          val expanded                    = JsonLd.expand(json).rightValue
          val expandedJson                = expanded.toJson()
          expandedJson should equalIgnoreArrayOrder(outJson)
        case _ => // ignore
      }
    }
  }
}
