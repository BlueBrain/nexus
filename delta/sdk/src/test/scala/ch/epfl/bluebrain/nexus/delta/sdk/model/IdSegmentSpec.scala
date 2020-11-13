package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class IdSegmentSpec extends AnyWordSpecLike with Matchers with Inspectors with OptionValues {

  private val am   = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  private val base = ProjectBase.unsafe(nxv.base)

  "An string segment" should {
    val list =
      List(
        "nxv:other"          -> (nxv + "other"),
        "Person"             -> schema.Person,
        "_"                  -> schemas.resources,
        "other"              -> (nxv + "other"),
        "http://example.com" -> iri"http://example.com"
      )

    "be converted to an Iri" in {
      forAll(list) { case (string, iri) =>
        StringSegment(string).toIri(am, base).value shouldEqual iri
      }
    }

    "failed to be converted to an Iri" in {
      StringSegment("#a?!*#").toIri(am, base) shouldEqual None
    }

  }

  "An Iri segment" should {
    val list =
      List(nxv + "other", schema.Person, schemas.resources, iri"http://example.com", iri"http://example.com")

    "be converted to an Iri" in {
      forAll(list) { iri =>
        IriSegment(iri).toIri(am, base).value shouldEqual iri
      }
    }
  }
}
