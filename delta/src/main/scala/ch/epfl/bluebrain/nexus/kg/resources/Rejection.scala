package ch.epfl.bluebrain.nexus.kg.resources

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Rejection => AkkaRejection}
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.kg.config.Contexts.errorCtxUri
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ValidationReport
import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.parser.parse
import io.circe.{Encoder, Json}

/**
  * Enumeration of resource rejection types.
  *
  * @param msg a descriptive message of the rejection
  */
sealed abstract class Rejection(val msg: String) extends AkkaRejection with Product with Serializable

object Rejection {

  /**
    * Signals an internal failure where the state of a resource is not the expected state.
    *
    * @param ref a reference to the resource
    */
  final case class UnexpectedState(ref: Ref) extends Rejection(s"Resource '${ref.show}' is in an unexpected state.")

  /**
    * Signals an attempt to interact with a resource that is deprecated.
    *
    * @param ref a reference to the resource
    */
  final case class ResourceIsDeprecated(ref: Ref) extends Rejection(s"Resource '${ref.show}' is deprecated.")

  /**
    * Signals an attempt to interact with a resource that is expected to be a file resource but it isn't.
    *
    * @param ref a reference to the resource
    */
  final case class NotAFileResource(ref: Ref) extends Rejection(s"Resource '${ref.show}' is not a file resource.")

  /**
    * Signals the missing digest computed for a file resource
    *
    * @param ref a reference to the resource
    */
  final case class FileDigestNotComputed(ref: Ref)
      extends Rejection(s"Resource '${ref.show}' does not have a computed digest.")

  /**
    * Signals an attempt to compute the digest for a file where the digest already exists.
    *
    * @param ref a reference to the resource
    */
  final case class FileDigestAlreadyExists(ref: Ref) extends Rejection(s"File '${ref.show}' digest already exists.")

  /**
    * Signals an attempt to perform a request with an invalid payload.
    *
    * @param ref a reference to the resource
    * @param details the human readable reason for the rejection
    */
  final case class InvalidResourceFormat(ref: Ref, details: String)
      extends Rejection(s"Resource '${ref.show}' has an invalid format.")

  /**
    * Signals an attempt to perform a request with an invalid JSON-LD payload.
    *
    * @param reason the human readable reason for the rejection
    */
  final case class InvalidJsonLD(reason: String) extends Rejection(s"Invalid payload due to '$reason'.")

  /**
    * Signals an attempt to interact with a resource that doesn't exist.
    *
    * @param ref       a reference to the resource
    * @param revOpt    an optional revision of the resource
    * @param tagOpt    an optional tag of the resource
    * @param schemaOpt an optional schema of the resource
    */
  final case class NotFound(
      ref: Ref,
      revOpt: Option[Long] = None,
      tagOpt: Option[String] = None,
      schemaOpt: Option[Ref] = None
  ) extends Rejection(
        ((revOpt, tagOpt) match {
          case (Some(rev), None) => s"Resource '${ref.show}' not found at revision $rev"
          case (None, Some(tag)) => s"Resource '${ref.show}' not found at tag '$tag'"
          case _                 => s"Resource '${ref.show}' not found"
        }) + schemaOpt.map(schema => s" for schema '${schema.show}'.").getOrElse(".")
      )
  object NotFound {
    def notFound(
        ref: Ref,
        rev: Option[Long] = None,
        tag: Option[String] = None,
        schema: Option[Ref] = None
    ): Rejection =
      NotFound(ref, rev, tag, schema)
  }

  /**
    * Signals an attempt to interact with a project that doesn't exist.
    *
    * @param ref a reference to the resource
    */
  final case class ProjectNotFound(ref: ProjectRef) extends Rejection(s"Project '${ref.show}' not found.")

  object ProjectNotFound {
    final def projectNotFound(ref: ProjectRef): Rejection = ProjectNotFound(ref)
  }

  /**
    * Signals an attempt to interact with one of the resources in the resource collection that doesn't exist.
    */
  final case object ArchiveElementNotFound
      extends Rejection(
        "Some of the resources in the archive were not found or you don't have the right permissions to fetch them."
      )

  /**
    * Signals the impossibility to resolve the project reference for project label.
    *
    * @param label the project label where the reference was not found
    */
  final case class ProjectRefNotFound(label: ProjectLabel)
      extends Rejection(s"Project reference for label '${label.show}' not found.")

  /**
    * Signals the impossibility to resolve the label for project references.
    *
    * @param project the project reference where the label was not found
    */
  final case class ProjectLabelNotFound(project: ProjectRef)
      extends Rejection(s"Label for project with reference '${project.show}' not found.")

  /**
    * Signals an attempt to interact with a resource with an incorrect revision.
    *
    * @param ref a reference to the resource
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(ref: Ref, provided: Long, expected: Long)
      extends Rejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the resource '${ref.show}' may have been updated since last seen."
      )

  /**
    * Signal an attempt to fetch view statistics for AggregateView.
    */
  final case object NoStatsForAggregateView
      extends Rejection("Statistics are not currently available for aggregate view")

  /**
    * Signals a mismatch between a resource representation and its id.
    *
    * @param ref a reference to the resource
    */
  final case class IncorrectId(ref: Ref)
      extends Rejection(s"Expected @id value '${ref.show}' was not found in the payload")

  /**
    * Signals an attempt to create a resource with wrong types on it's payload.
    *
    * @param ref   a reference to the resource
    * @param types the payload types
    */
  final case class IncorrectTypes(ref: Ref, types: Set[AbsoluteIri])
      extends Rejection(s"Resource '${ref.show}' with incorrect payload types '$types'.")

  /**
    * Signals an attempt to create a resource that already exists.
    *
    * @param ref a reference to the resource
    */
  final case class ResourceAlreadyExists(ref: Ref) extends Rejection(s"Resource '${ref.show}' already exists.")

  /**
    * Signals that a resource has an illegal (transitive) context value.
    *
    * @param refs the import value stack
    */
  final case class IllegalContextValue(refs: List[Ref])
      extends Rejection(
        s"Resource '${refs.reverseIterator.map(_.show).to(List).mkString(" -> ")}' has an illegal context value."
      )

  /**
    * Signals that the system is unable to select a primary node from a resource graph.
    */
  final case object UnableToSelectResourceId
      extends Rejection("Resource is not entity centric, unable to select primary node.")
  type UnableToSelectResourceId = UnableToSelectResourceId.type

  /**
    * Signals that a resource validation failed.
    *
    * @param schema a reference to the schema
    * @param report the validation report
    */
  final case class InvalidResource(schema: Ref, report: ValidationReport)
      extends Rejection(s"Resource failed to validate against the constraints defined by '${schema.show}'")

  /**
    * Signals that the logged caller does not have one of the provided identities
    *
    */
  final case class InvalidIdentity(reason: String = "The caller doesn't have some of the provided identities")
      extends Rejection(reason)

  @nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
  implicit val rejectionEncoder: Encoder[Rejection] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[Rejection].mapJson(_ addContext errorCtxUri)
    def reason(r: Rejection): Json              =
      Json.obj("reason" -> Json.fromString(r.msg))

    def tpe(r: Rejection): Json                 =
      enc(r).hcursor.get[String]("@type").map(tpe => Json.obj("@type" -> Json.fromString(tpe))).getOrElse(Json.obj())

    def details(r: InvalidResourceFormat): Json =
      parse(r.details)
        .map(value => Json.obj("details" -> value))
        .getOrElse(Json.obj("details" -> Json.fromString(r.details)))

    Encoder.instance {
      case r: InvalidResourceFormat => (tpe(r) deepMerge reason(r) deepMerge details(r)).addContext(errorCtxUri)
      case r: InvalidResource       => enc(r) deepMerge reason(r)
      case r                        => (tpe(r) deepMerge reason(r)).addContext(errorCtxUri)
    }
  }

  implicit def statusCodeFrom: StatusFrom[Rejection] =
    StatusFrom {
      case _: FileDigestNotComputed    => StatusCodes.BadRequest
      case _: ResourceIsDeprecated     => StatusCodes.BadRequest
      case _: IncorrectTypes           => StatusCodes.BadRequest
      case _: IllegalContextValue      => StatusCodes.BadRequest
      case _: UnableToSelectResourceId => StatusCodes.BadRequest
      case _: InvalidResource          => StatusCodes.BadRequest
      case _: IncorrectId              => StatusCodes.BadRequest
      case _: InvalidResourceFormat    => StatusCodes.BadRequest
      case _: InvalidJsonLD            => StatusCodes.BadRequest
      case _: NotAFileResource         => StatusCodes.BadRequest
      case NoStatsForAggregateView     => StatusCodes.BadRequest
      case _: UnexpectedState          => StatusCodes.InternalServerError
      case ArchiveElementNotFound      => StatusCodes.NotFound
      case _: ProjectLabelNotFound     => StatusCodes.NotFound
      case _: NotFound                 => StatusCodes.NotFound
      case _: ProjectNotFound          => StatusCodes.NotFound
      case _: ProjectRefNotFound       => StatusCodes.NotFound
      case _: IncorrectRev             => StatusCodes.Conflict
      case _: ResourceAlreadyExists    => StatusCodes.Conflict
      case _: FileDigestAlreadyExists  => StatusCodes.Conflict
      case _: InvalidIdentity          => StatusCodes.Unauthorized
    }
}
