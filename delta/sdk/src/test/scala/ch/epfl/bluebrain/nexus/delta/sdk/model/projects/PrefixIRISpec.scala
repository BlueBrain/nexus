package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.{IllegalIRIFormatError, IllegalPrefixIRIFormatError}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PrefixIRISpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable {
  "A PrefixIRI" should {

    "be an iri ending with / or #" in {
      forAll(List(xsd.base, schema.base, owl.base, rdf.base)) { iri =>
        PrefixIRI.apply(iri).rightValue.value shouldEqual iri
        PrefixIRI(iri.toString).rightValue.value shouldEqual iri
      }
    }

    "reject to be constructed" in {
      forAll(List(xsd.int, schema.Person, rdf.tpe)) { iri =>
        PrefixIRI(iri).leftValue shouldBe a[IllegalPrefixIRIFormatError]
      }
      PrefixIRI("f7*#?n\\?#3").leftValue shouldBe a[IllegalIRIFormatError]
    }
  }
}
