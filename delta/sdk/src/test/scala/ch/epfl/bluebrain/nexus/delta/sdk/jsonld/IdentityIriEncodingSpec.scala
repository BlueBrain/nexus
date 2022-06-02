package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatErrors.{IllegalIdentityIriFormatError, IllegalSubjectIriFormatError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class IdentityIriEncodingSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable {

  implicit private val base: BaseUri                = BaseUri("http://localhost:8080", Label.unsafe("v1"))
  private val realm                                 = Label.unsafe("myrealm")
  private val list: Seq[(IriOrBNode.Iri, Identity)] = List(
    iri"http://localhost:8080/v1/anonymous"                    -> Anonymous,
    iri"http://localhost:8080/v1/realms/$realm/users/myuser"   -> User("myuser", realm),
    iri"http://localhost:8080/v1/realms/$realm/groups/mygroup" -> Group("mygroup", realm),
    iri"http://localhost:8080/v1/realms/$realm/authenticated"  -> Authenticated(realm)
  )

  "An Identity" should {

    "be converted to an Iri" in {
      forAll(list) { case (iri, identity) =>
        identity.asIri shouldEqual iri
      }
    }

    "be created from an Iri" in {
      forAll(list) { case (iri, identity) =>
        iri.as[Identity].rightValue shouldEqual identity
      }
    }

    "failed to be created from an Iri" in {
      val failed = List(
        iri"http://localhost:8080/v1/other/anonymous",
        iri"http://localhost:8081/v1/anonymous",
        iri"http://localhost:8080/v1/realms/$realm/users/myuser/other"
      )
      forAll(failed) { iri =>
        iri.as[Identity].leftValue shouldBe a[IllegalIdentityIriFormatError]
      }
    }
  }

  "An Subject" should {

    "be converted to an Iri" in {
      forAll(list.take(2)) { case (iri, identity) =>
        identity.asIri shouldEqual iri
      }
    }

    "be created from an Iri" in {
      forAll(list.take(2)) { case (iri, identity) =>
        iri.as[Subject].rightValue shouldEqual identity.asInstanceOf[Subject]
      }
    }

    "failed to be created from an Iri" in {
      val failed = List(
        iri"http://localhost:8080/v1/other/anonymous",
        iri"http://localhost:8081/v1/anonymous",
        iri"http://localhost:8080/v1/realms/$realm/users/myuser/other"
      ) ++ list.slice(2, 4).map(_._1)
      forAll(failed) { iri =>
        iri.as[Subject].leftValue shouldBe a[IllegalSubjectIriFormatError]
      }
    }
  }
}
