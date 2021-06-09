package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class IdSegmentSpec extends AnyWordSpecLike with Matchers with Inspectors with OptionValues {

  private val am   = ApiMappings(
    "nxv"      -> nxv.base,
    "data"     -> schemas.base,
    "Person"   -> schema.Person,
    "_"        -> schemas.resources,
    "resource" -> schemas.resources
  )
  private val base = ProjectBase.unsafe(nxv.base)

  "An string segment" should {
    val list =
      List(
        "data:other"         -> (schemas + "other"),
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
      List(
        nxv + "other"           -> (nxv + "other"),
        schema.Person           -> schema.Person,
        schemas.resources       -> schemas.resources,
        iri"http://example.com" -> iri"http://example.com",
        iri"data:other"         -> (schemas + "other")
      )

    "be converted to an Iri" in {
      forAll(list) { case (iri, expected) =>
        IriSegment(iri).toIri(am, base).value shouldEqual expected
      }
    }
  }
}
