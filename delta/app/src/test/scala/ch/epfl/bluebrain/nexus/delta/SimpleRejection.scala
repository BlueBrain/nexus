package ch.epfl.bluebrain.nexus.delta

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Allow
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.routes.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.syntax._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}
import monix.bio.IO

import scala.annotation.nowarn

sealed trait SimpleRejection {
  def reason: String
}

object SimpleRejection extends CirceLiteral {

  final case class BadRequestRejection(reason: String) extends SimpleRejection
  final case class ConflictRejection(reason: String)   extends SimpleRejection

  final val conflictRejection: SimpleRejection   = ConflictRejection("default conflict rejection")
  final val badRequestRejection: SimpleRejection = BadRequestRejection("default bad request rejection")

  val contextIri: Iri = iri"http://example.com/contexts/simple-rejection.json"

  val context: Json = json"""{ "@context": {"@vocab": "${nxv.base}"} }"""

  val bNode: BNode = BNode.random

  implicit val jsonLdEncoderSimpleRejection: JsonLdEncoder[SimpleRejection] =
    new JsonLdEncoder[SimpleRejection] {

      @nowarn("cat=unused")
      implicit val cfg: Configuration =
        Configuration.default.withDiscriminator("@type")

      implicit private val simpleRejectionEncoder: Encoder.AsObject[SimpleRejection] =
        deriveConfiguredEncoder[SimpleRejection]

      override def apply(v: SimpleRejection): IO[RdfError, JsonLd] =
        IO.pure(JsonLd.compactedUnsafe(v.asJsonObject, defaultContext, bNode))

      override val defaultContext: RawJsonLdContext = RawJsonLdContext(contextIri.asJson)
    }

  implicit val statusFromSimpleRejection: HttpResponseFields[SimpleRejection] =
    HttpResponseFields.fromStatusAndHeaders {
      case _: BadRequestRejection => (StatusCodes.BadRequest, Seq(Allow(GET)))
      case _: ConflictRejection   => (StatusCodes.Conflict, Seq.empty)
    }
}
