package ai.senscience.nexus.delta.plugins.archive.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of archive rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class ArchiveRejection(val reason: String) extends Rejection

object ArchiveRejection {

  /**
    * Rejection returned when attempting to create an archive but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends ArchiveRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when there's at least a path collision in the archive.
    *
    * @param duplicates
    *   the paths that have been repeated
    * @param invalids
    *   the paths that are invalids
    * @param longIds
    *   long ids for which a path must be defined
    */
  final case class InvalidResourceCollection(
      duplicates: Set[AbsolutePath],
      invalids: Set[AbsolutePath],
      longIds: Set[Iri]
  ) extends ArchiveRejection(
        (Option.when(duplicates.nonEmpty)(
          s"The paths '${duplicates.mkString(", ")}' have been used multiple times in the archive."
        ) ++
          Option.when(invalids.nonEmpty)(
            s"The paths '${invalids.mkString(", ")}' must have a file path prefix less than 154 characters long and a file path name less than 99 characters long."
          ) ++
          Option.when(longIds.nonEmpty)(
            s"The resource ids '${longIds.mkString(", ")}' generate a file name exceeding 99 characters, please define a path for each of them."
          )).mkString("\n")
      )

  /**
    * Rejection returned when an archive doesn't exist.
    *
    * @param id
    *   the archive id
    * @param project
    *   the archive parent project
    */
  final case class ArchiveNotFound(id: Iri, project: ProjectRef)
      extends ArchiveRejection(s"Archive '$id' not found in project '$project'.")

  /**
    * A file self from the archive definition was invalid
    */
  final case class InvalidFileSelf(underlying: FileSelf.ParsingError) extends ArchiveRejection(underlying.message)

  /**
    * Rejection returned when attempting to interact with an Archive while providing an id that cannot be resolved to an
    * Iri.
    *
    * @param id
    *   the archive identifier
    */
  final case class InvalidArchiveId(id: String)
      extends ArchiveRejection(s"Archive identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when a referenced resource could not be found.
    *
    * @param ref
    *   the resource reference
    * @param project
    *   the project reference
    */
  final case class ResourceNotFound(ref: ResourceRef, project: ProjectRef)
      extends ArchiveRejection(s"The resource '${ref.toString}' was not found in project '$project'.")

  /**
    * Wrapper for file rejections.
    *
    * @param rejection
    *   the underlying file rejection
    */
  final case class WrappedFileRejection(rejection: FileRejection) extends ArchiveRejection(rejection.reason)

  implicit final val archiveRejectionEncoder: Encoder.AsObject[ArchiveRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case InvalidResourceCollection(duplicates, invalids, longIds) =>
          obj.add("duplicates", duplicates.asJson).add("invalids", invalids.asJson).add("longIds", longIds.asJson)
        case _: ArchiveNotFound                                       => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                                        => obj
      }
    }

  implicit final val archiveRejectionJsonLdEncoder: JsonLdEncoder[ArchiveRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit final val archiveResponseFields: HttpResponseFields[ArchiveRejection] =
    HttpResponseFields {
      case ResourceAlreadyExists(_, _)        => StatusCodes.Conflict
      case InvalidResourceCollection(_, _, _) => StatusCodes.BadRequest
      case ArchiveNotFound(_, _)              => StatusCodes.NotFound
      case InvalidArchiveId(_)                => StatusCodes.BadRequest
      case ResourceNotFound(_, _)             => StatusCodes.NotFound
      case InvalidFileSelf(_)                 => StatusCodes.BadRequest
      case WrappedFileRejection(rejection)    => rejection.status
    }
}
