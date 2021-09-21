package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Top level error type that represents general errors
  */
sealed abstract class ServiceError(val reason: String) extends SDKError {

  override def getMessage: String = reason
}

object ServiceError {

  /**
    * Signals that the authorization failed
    */
  final case object AuthorizationFailed
      extends ServiceError("The supplied authentication is not authorized to access this resource.")

  /**
    * Signals that an organization or project initialization has failed.
    *
    * @param reason
    *   the underlying cause for the failure
    */
  final case class ScopeInitializationFailed(override val reason: String) extends ServiceError(reason)

  sealed abstract class IndexingActionFailed(override val reason: String, val resource: ResourceF[Unit])
      extends ServiceError(reason)

  final case class IndexingFailed(override val reason: String, override val resource: ResourceF[Unit])
      extends IndexingActionFailed(reason, resource)

  @nowarn("cat=unused")
  implicit def serviceErrorEncoder(implicit baseUri: BaseUri): Encoder.AsObject[ServiceError] = {
    implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                   = deriveConfiguredEncoder[ServiceError]
    Encoder.AsObject.instance[ServiceError] { r =>
      enc.encodeObject(r).+:("reason" -> Json.fromString(r.reason))
    }
  }

  implicit def serviceErrorJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[ServiceError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  @nowarn("cat=unused")
  implicit def consistentWriteFailedEncoder(implicit baseUri: BaseUri): Encoder.AsObject[IndexingActionFailed] = {
    implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                   = deriveConfiguredEncoder[ServiceError]
    Encoder.AsObject.instance[IndexingActionFailed] { r =>
      enc.encodeObject(r).add("reason", Json.fromString(r.reason)).add("_resource", r.resource.asJson)
    }
  }

  implicit def consistentWriteFailedJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[IndexingActionFailed] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
