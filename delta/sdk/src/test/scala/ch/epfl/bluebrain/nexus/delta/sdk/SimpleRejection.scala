package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Allow
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

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

  @nowarn("cat=unused")
  implicit private val cfg: Configuration =
    Configuration.default.withDiscriminator("@type")

  implicit private val simpleRejectionEncoder: Encoder.AsObject[SimpleRejection] =
    deriveConfiguredEncoder[SimpleRejection]

  implicit val jsonLdEncoderSimpleRejection: JsonLdEncoder[SimpleRejection] =
    JsonLdEncoder.computeFromCirce(bNode, ContextValue(contextIri))

  implicit val statusFromSimpleRejection: HttpResponseFields[SimpleRejection] =
    HttpResponseFields.fromStatusAndHeaders {
      case _: BadRequestRejection => (StatusCodes.BadRequest, Seq(Allow(GET)))
      case _: ConflictRejection   => (StatusCodes.Conflict, Seq.empty)
    }
}
