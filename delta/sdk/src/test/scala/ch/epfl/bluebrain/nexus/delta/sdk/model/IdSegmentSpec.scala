package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, ResourceRefSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class IdSegmentSpec extends AnyWordSpecLike with Matchers with Inspectors with OptionValues {

  private val am               = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  implicit private val project = ProjectGen.project("org", "proj", mappings = am)

  "An string segment" should {
    val list =
      List(
        "nxv:other"          -> (nxv + "other"),
        "Person"             -> schema.Person,
        "_"                  -> schemas.resources,
        "http://example.com" -> iri"http://example.com"
      )

    "be converted to an Iri" in {
      forAll(list) { case (string, iri) =>
        StringSegment(string).toIri.value shouldEqual iri
      }
    }

    "failed to be converted to an Iri" in {
      StringSegment("a:*#").toIri shouldEqual None
    }

    "be converted to a ResourceRef" in {
      forAll(list) { case (string, iri) =>
        StringSegment(string).toResourceRef.value shouldEqual Latest(iri)
      }
    }
  }

  "An Iri segment" should {
    val list =
      List(nxv + "other", schema.Person, schemas.resources, iri"http://example.com", iri"http://example.com")

    "be converted to an Iri" in {
      forAll(list) { iri =>
        IriSegment(iri).toIri.value shouldEqual iri
      }
    }

    "be converted to a ResourceRef" in {
      forAll(list) { iri =>
        IriSegment(iri).toResourceRef.value shouldEqual Latest(iri)
      }
    }
  }

  "A ResourceRef segment" should {
    val list =
      List(
        Latest(nxv + "other"),
        Tag(iri"http://example.com?tag=mytag", iri"http://example.com", Label.unsafe("mytag")),
        Revision(iri"http://example.com?rev=2", iri"http://example.com", 2L)
      )

    "be converted to an Iri" in {
      forAll(list) { resourceRef =>
        ResourceRefSegment(resourceRef).toIri.value shouldEqual resourceRef.original
      }
    }

    "be converted to a ResourceRef" in {
      forAll(list) { resourceRef =>
        ResourceRefSegment(resourceRef).toResourceRef.value shouldEqual resourceRef
      }
    }
  }

}
