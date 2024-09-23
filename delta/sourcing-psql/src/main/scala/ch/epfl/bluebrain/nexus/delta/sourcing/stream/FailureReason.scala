package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ThrowableUtils.stackTraceAsString
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Codec, Encoder, Json}

case class FailureReason(`type`: String, message: String, details: Json)

object FailureReason {

  implicit private val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)

  implicit val failureReasonCodec: Codec.AsObject[FailureReason] = deriveConfiguredCodec[FailureReason]

  def apply(throwable: Throwable): FailureReason =
    apply(throwable.getClass.getCanonicalName, throwable.getMessage, Some(stackTraceAsString(throwable)))

  def apply(errorType: String, message: String, stackTrace: Option[String]): FailureReason =
    FailureReason(
      "UnexpectedError",
      message,
      Json.obj(
        "exception"  := errorType,
        "stacktrace" := stackTrace
      )
    )

  def apply[A: Encoder](tpe: String, message: String, value: A): FailureReason =
    FailureReason(
      tpe,
      message,
      value.asJson
    )

}
