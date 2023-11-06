package ch.epfl.bluebrain.nexus.delta.sdk.error

import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
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
  final case class AuthorizationFailed(details: String)
      extends ServiceError("The supplied authentication is not authorized to access this resource.")

  object AuthorizationFailed {

    private def missingPermission(path: AclAddress, permission: Permission) =
      s"Permission '$permission' is missing on '$path'."

    private def onRequest(request: HttpRequest) = s"Incoming request was '${request.uri}' ('${request.method.value}')."

    def apply(request: HttpRequest): AuthorizationFailed = AuthorizationFailed(onRequest(request))

    def apply(request: HttpRequest, path: AclAddress, permission: Permission): AuthorizationFailed = {
      val details = List(missingPermission(path, permission), onRequest(request)).mkString("\n")
      AuthorizationFailed(details)
    }

    def apply(path: AclAddress, permission: Permission): AuthorizationFailed =
      AuthorizationFailed(missingPermission(path, permission))

  }

  /**
    * Signals that an organization or project initialization has failed.
    *
    * @param reason
    *   the underlying cause for the failure
    */
  final case class ScopeInitializationFailed(override val reason: String) extends ServiceError(reason)

  /**
    * Signals when fetch a project context for a given project
    */
  final case class FetchContextFailed(project: ProjectRef)
      extends ServiceError(s"Fetching the context for the project '$project' failed.")

  final case class IndexingFailed(resource: ResourceF[Unit], errors: List[Throwable])
      extends ServiceError(errors.map(_.getMessage).mkString("* ", "\n* ", ""))

  /**
    * Signals that the SSE label can't be found
    */
  final case class UnknownSseLabel(label: Label) extends ServiceError(s"The SSE label $label is unknown.")

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
  implicit def indexingFailedEncoder(implicit baseUri: BaseUri): Encoder.AsObject[IndexingFailed] = {
    implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                   = deriveConfiguredEncoder[ServiceError]
    Encoder.AsObject.instance[IndexingFailed] { r =>
      enc.encodeObject(r).add("reason", Json.fromString(r.reason)).add("_resource", r.resource.asJson)
    }
  }

  implicit def consistentWriteFailedJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[IndexingFailed] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsServiceError: HttpResponseFields[ServiceError] =
    HttpResponseFields {
      case AuthorizationFailed(_)       => StatusCodes.Forbidden
      case FetchContextFailed(_)        => StatusCodes.InternalServerError
      case ScopeInitializationFailed(_) => StatusCodes.InternalServerError
      case IndexingFailed(_, _)         => StatusCodes.InternalServerError
      case UnknownSseLabel(_)           => StatusCodes.InternalServerError
    }
}
