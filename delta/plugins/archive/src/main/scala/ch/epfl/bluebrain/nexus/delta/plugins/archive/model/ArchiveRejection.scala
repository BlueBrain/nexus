package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields.responseFieldsProjects
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.{EvaluationError, EvaluationFailure, EvaluationTimeout}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

import scala.reflect.ClassTag

/**
  * Enumeration of archive rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ArchiveRejection(val reason: String) extends Product with Serializable

object ArchiveRejection {

  /**
    * Rejection returned when attempting to create an archive but the id already exists.
    *
    * @param id      the resource identifier
    * @param project the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends ArchiveRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when there's at least a path collision in the archive.
    *
    * @param paths the offending paths
    */
  final case class DuplicateResourcePath(paths: Set[AbsolutePath])
      extends ArchiveRejection(s"The paths '${paths.mkString(", ")}' have been used multiple times in the archive.")

  /**
    * Rejection returned when an archive doesn't exist.
    *
    * @param id      the archive id
    * @param project the archive parent project
    */
  final case class ArchiveNotFound(id: Iri, project: ProjectRef)
      extends ArchiveRejection(s"Archive '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to interact with an Archive while providing an id that cannot be
    * resolved to an Iri.
    *
    * @param id the archive identifier
    */
  final case class InvalidArchiveId(id: String)
      extends ArchiveRejection(s"Archive identifier '$id' cannot be expanded to an Iri.")

  /**
    * Wrapper for project rejections.
    *
    * @param rejection the underlying project rejection
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends ArchiveRejection(rejection.reason)

  /**
    * Rejection returned when the returned state is the initial state after a successful command evaluation.
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a
    * non initial state.
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends ArchiveRejection(s"Unexpected initial state for Archive '$id' of project '$project'.")

  /**
    * Rejection returned when attempting to create an Archive where the passed id does not match the id on the
    * source json document.
    *
    * @param id       the archive identifier
    * @param sourceId the archive identifier in the source json document
    */
  final case class UnexpectedArchiveId(id: Iri, sourceId: Iri)
      extends ArchiveRejection(
        s"The provided Archive '$id' does not match the id '$sourceId' in the source document."
      )

  /**
    * Rejection returned when attempting to decode an expanded JsonLD as an Archive.
    *
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends ArchiveRejection(error.getMessage)

  /**
    * Rejection returned when converting the source Json document to a JsonLD document.
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends ArchiveRejection(
        s"The provided Archive JSON document ${id.fold("")(id => s"with id '$id'")} cannot be interpreted as a JSON-LD document."
      )

  /**
    * Rejection returned when a referenced resource could not be found.
    *
    * @param ref     the resource reference
    * @param project the project reference
    */
  final case class ResourceNotFound(ref: ResourceRef, project: ProjectRef)
      extends ArchiveRejection(s"The resource '${ref.toString}' was not found in project '$project'.")

  /**
    * Rejection returned when the caller does not have permission to access a referenced resource.
    *
    * @param address    the address on which the permission was checked
    * @param permission the permission that was required
    */
  final case class AuthorizationFailed(address: AclAddress, permission: Permission)
      extends ArchiveRejection(
        s"The permission '$permission' required for accessing a resource at '$address' is missing."
      )

  /**
    * Wrapper for file rejections.
    *
    * @param rejection the underlying file rejection
    */
  final case class WrappedFileRejection(rejection: FileRejection) extends ArchiveRejection(rejection.reason)

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class ArchiveEvaluationError(err: EvaluationError) extends ArchiveRejection("Unexpected evaluation error")

  implicit final val projectToArchiveRejectionMapper: Mapper[ProjectRejection, ArchiveRejection] =
    (value: ProjectRejection) => WrappedProjectRejection(value)

  implicit final val jsonLdRejectionMapper: Mapper[JsonLdRejection, ArchiveRejection] = {
    case JsonLdRejection.UnexpectedId(id, sourceId)        => UnexpectedArchiveId(id, sourceId)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit final def archiveRejectionEncoder(implicit C: ClassTag[CreateArchive]): Encoder.AsObject[ArchiveRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case ArchiveEvaluationError(EvaluationFailure(C(cmd), _)) =>
          val reason = s"Unexpected failure while evaluating the command '${simpleName(cmd)}' for archive '${cmd.id}'"
          JsonObject(keywords.tpe -> "ArchiveEvaluationFailure".asJson, "reason" -> reason.asJson)
        case ArchiveEvaluationError(EvaluationTimeout(C(cmd), t)) =>
          val reason = s"Timeout while evaluating the command '${simpleName(cmd)}' for archive '${cmd.id}' after '$t'"
          JsonObject(keywords.tpe -> "ArchiveEvaluationTimeout".asJson, "reason" -> reason.asJson)
        case WrappedProjectRejection(rejection)                   => rejection.asJsonObject
        case InvalidJsonLdFormat(_, rdf)                          => obj.add("rdf", rdf.asJson)
        case _                                                    => obj
      }
    }

  implicit final val archiveRejectionJsonLdEncoder: JsonLdEncoder[ArchiveRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit final val evaluationErrorMapper: Mapper[EvaluationError, ArchiveRejection] = ArchiveEvaluationError.apply

  implicit final val archiveResponseFields: HttpResponseFields[ArchiveRejection] =
    HttpResponseFields {
      case ResourceAlreadyExists(_, _)        => StatusCodes.Conflict
      case DuplicateResourcePath(_)           => StatusCodes.BadRequest
      case ArchiveNotFound(_, _)              => StatusCodes.NotFound
      case InvalidArchiveId(_)                => StatusCodes.BadRequest
      case WrappedProjectRejection(rejection) => rejection.status
      case UnexpectedInitialState(_, _)       => StatusCodes.InternalServerError
      case UnexpectedArchiveId(_, _)          => StatusCodes.BadRequest
      case DecodingFailed(_)                  => StatusCodes.BadRequest
      case InvalidJsonLdFormat(_, _)          => StatusCodes.BadRequest
      case ResourceNotFound(_, _)             => StatusCodes.NotFound
      case AuthorizationFailed(_, _)          => StatusCodes.Forbidden
      case WrappedFileRejection(rejection)    => rejection.status
      case ArchiveEvaluationError(_)          => StatusCodes.BadRequest
    }
}
