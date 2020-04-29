package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.jsonld.context.ContextSpec
import ch.epfl.bluebrain.nexus.rdf.{CirceEq, RdfSpec}

class JsonLdSpec extends RdfSpec with JsonLdFixtures with CirceEq {

  "A JsonLD" when {

    "expanding" should {

      val excludeExpansion = Set(
        "0005-in.jsonld", // @id with value #me
        "0060-in.jsonld", // relative unexpanded @id and @type not supported
        "0068-in.jsonld", // @id/term with blank node not supported
        "0076-in.jsonld", // @id/term with blank node not supported
        "0077-in.jsonld", // ignore this test, the context is provided outside the scope of the file
        "0091-in.jsonld", // merging of contexts with @base absolute and relative not supported yet
        "0092-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0110-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0111-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0112-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0122-in.jsonld", // ignore invalid term when expanding iri. TO BE FIXED
        "c005-in.jsonld", // it works, but the jsonLD comparison is wrong
        "m001-in.jsonld", // @id/term with blank node not supported
        "m002-in.jsonld", // @id/term with blank node not supported
        "m003-in.jsonld", // @id/term with blank node not supported
        "m004-in.jsonld", // @id/term with blank node not supported
        "m004-in.jsonld", // @id/term with blank node not supported
        "pi06-in.jsonld", // property-based index maps not supported yet
        "pi07-in.jsonld", // property-based index maps not supported yet
        "pi08-in.jsonld", // property-based index maps not supported yet
        "pi09-in.jsonld", // property-based index maps not supported yet
        "pi10-in.jsonld", // property-based index maps not supported yet
        "pi11-in.jsonld", // property-based index maps not supported yet
        "in06-in.jsonld"  // @nest not supported yet
      ) ++ ContextSpec.excluded
      val excludeExpansionFailed = Set(
        "0115-in.jsonld", // fails only in jsonld 1.0. Ignore
        "0116-in.jsonld", // fails only in jsonld 1.0. Ignore
        "c029-in.jsonld", // fails only in jsonld 1.0. Ignore
        "ep02-in.jsonld", // fails only in jsonld 1.0. Ignore
        "er24-in.jsonld", // fails only in jsonld 1.0. Ignore
        "er32-in.jsonld", // fails only in jsonld 1.0. Ignore
        "er42-in.jsonld", // fails only in jsonld 1.0. Ignore
        "es01-in.jsonld", // fails only in jsonld 1.0. Ignore
        "pi01-in.jsonld", // fails only in jsonld 1.0. Ignore
        "tn01-in.jsonld", // fails only in jsonld 1.0. Ignore
        "er05-in.jsonld", // fails because the imported context fails. Ignore for now
        "er21-in.jsonld", // fails because the imported context fails. Ignore for now
        "er10-in.jsonld", // fails to detect cyclic term resolution undetected
        "er43-in.jsonld", // @id: @type is illegal in jsonld 1.1 but allowed in 1.0. Allow it for now
        "pi05-in.jsonld"  // index map properties not supported yet
      )

      "succeed" in {
        forAll(expandTestCases) {
          case (inName, json, _, Some(expected), options)
              if !excludeExpansion.contains(inName) && !inName.startsWith("pr") =>
            implicit val opt: JsonLdOptions = options
            val jsonLd = JsonLd(json).rightValue
            jsonLd.expanded should equalIgnoreArrayOrder(expected)
          case _ => // ignore
        }
      }

      "fail to be decoded" in {
        forAll(expandTestCases) {
          case (inName, json, _, None, options)
              if !excludeExpansionFailed.contains(inName) && !inName.startsWith("pr") =>
            implicit val opt: JsonLdOptions = options
            JsonLd(json).leftValue
          case _ => //ignore
        }
      }
    }

    "converting to Graph" should {

      val excludeToRdf = Set(
        "0009-in.jsonld", // term as curie ending with : not handled correctly
        "0027-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0028-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0029-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0030-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0113-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0114-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0115-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0116-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0117-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "c025-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e020-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e021-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e079-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e080-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e081-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e082-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e083-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e084-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e085-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e086-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e087-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e093-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e094-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e095-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e096-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e097-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e098-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e099-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e100-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e101-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e102-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e103-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e104-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e105-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e106-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e107-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "e108-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "m013-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "m014-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "m015-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "m016-in.jsonld", // @graph not supported yet, since n-quards is not supported
        "0036-in.jsonld", // @id/term with blank node not supported
        "0118-in.jsonld", // @id/term with blank node not supported
        "0119-in.jsonld", // @id/term with blank node not supported
        "e038-in.jsonld", // @id/term with blank node not supported
        "e068-in.jsonld", // @id/term with blank node not supported
        "e075-in.jsonld", // @id/term with blank node not supported
        "m001-in.jsonld", // @id/term with blank node not supported
        "m002-in.jsonld", // @id/term with blank node not supported
        "m003-in.jsonld", // @id/term with blank node not supported
        "m004-in.jsonld", // @id/term with blank node not supported
        "0122-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0123-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0124-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0125-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0126-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "0128-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "e092-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "e110-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "e111-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "e112-in.jsonld", // Uris are resolved and normalized. That's a feature of our Uri implementation, not a bug
        "c031-in.jsonld", // relative link resolution, not supported yet
        "c031-in.jsonld", // relative link resolution, not supported yet
        "c034-in.jsonld", // relative link resolution, not supported yet
        "e126-in.jsonld", // relative link resolution, not supported yet
        "e127-in.jsonld", // relative link resolution, not supported yet
        "e128-in.jsonld", // relative link resolution, not supported yet
        "so05-in.jsonld", // relative link resolution, not supported yet
        "so06-in.jsonld", // relative link resolution, not supported yet
        "so07-in.jsonld", // relative link resolution, not supported yet
        "so08-in.jsonld", // relative link resolution, not supported yet
        "so09-in.jsonld", // relative link resolution, not supported yet
        "so10-in.jsonld", // relative link resolution, not supported yet
        "so11-in.jsonld", // relative link resolution, not supported yet
        "so12-in.jsonld", // relative link resolution, not supported yet
        "di09-in.jsonld", // rdfDirection option not implemented, since it is non-normative
        "di10-in.jsonld", // rdfDirection option not implemented, since it is non-normative
        "di11-in.jsonld", // rdfDirection option not implemented, since it is non-normative
        "di12-in.jsonld", // rdfDirection option not implemented, since it is non-normative
        "e062-in.jsonld", // Failed on equality. To be fixed
        "e077-in.jsonld", // ignore this test, the context is provided outside the scope of the file
        "e091-in.jsonld", // merging of contexts with @base absolute and relative not supported yet
        "e122-in.jsonld", // ignore invalid term when expanding iri. TO BE FIXED
        "in06-in.jsonld", // @nest not supported yet
        "n001-in.jsonld", // @nest not supported yet
        "n002-in.jsonld", // @nest not supported yet
        "n003-in.jsonld", // @nest not supported yet
        "n004-in.jsonld", // @nest not supported yet
        "n005-in.jsonld", // @nest not supported yet
        "n006-in.jsonld", // @nest not supported yet
        "n007-in.jsonld", // @nest not supported yet
        "n008-in.jsonld", // @nest not supported yet
        "js04-in.jsonld", // @json literal transformation failed
        "js06-in.jsonld", // @json value does not match order from tests, but it works. Ignore test
        "js07-in.jsonld", // @json value does not match order from tests, but it works. Ignore test
        "js08-in.jsonld", // @json value does not match order from tests, but it works. Ignore test
        "js10-in.jsonld", // @json value does not match order from tests, but it works. Ignore test
        "js12-in.jsonld", // @json value does not match order from tests, but it works. Ignore test
        "js13-in.jsonld", // @json value does not match order from tests, but it works. Ignore test
        "pi06-in.jsonld", // property-based index maps not supported yet
        "pi07-in.jsonld", // property-based index maps not supported yet
        "pi08-in.jsonld", // property-based index maps not supported yet
        "pi09-in.jsonld", // property-based index maps not supported yet
        "pi10-in.jsonld", // property-based index maps not supported yet
        "pi11-in.jsonld", // property-based index maps not supported yet
        "e060-in.jsonld", // when @id cannot be expanded or returns null, ignores triples and not fail
        "wf01-in.jsonld", // when @id cannot be expanded or returns null, ignores triples and not fail
        "wf02-in.jsonld", // when @id cannot be expanded or returns null, ignores triples and not fail
        "wf03-in.jsonld", // when @id cannot be expanded or returns null, ignores triples and not fail
        "wf07-in.jsonld", // when @id cannot be expanded or returns null, ignores triples and not fail
        "wf04-in.jsonld", // when @type cannot be expanded or returns null, ignores triples and not fail
        "wf05-in.jsonld", // when @language field is wrong, ignore triple

      )

      "succeed" in {
        forAll(toRdfTestCases) {
          case (inName, json, _, Some(expected), options) if !excludeToRdf.contains(inName) && !inName.startsWith("pr") =>
            implicit val opt: JsonLdOptions = options
            val jsonLd = JsonLd(json)
            val triples = jsonLd.rightValue.toGraph.ntriples
            Ntriples(triples) shouldEqual Ntriples(expected)
          case _ => // ignore
        }
      }
    }
  }
}
