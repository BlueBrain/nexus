package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphErrorParser
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

sealed abstract class BlazegraphViewRejection(val reason: String) extends Rejection

object BlazegraphViewRejection {

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends BlazegraphViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: UserTag) extends BlazegraphViewRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a blazegraph view but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update a view that doesn't exist.
    *
    * @param id
    *   the view id
    * @param project
    *   the project it belongs to
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Blazegraph view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id
    *   the view id
    */
  final case class ViewIsDeprecated(id: Iri) extends BlazegraphViewRejection(s"Blazegraph view '$id' is deprecated.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id
    *   the view id
    */
  final case class ViewIsNotDeprecated(id: Iri)
      extends BlazegraphViewRejection(s"Blazegraph view '$id' is not deprecated.")

  /**
    * Rejection returned when attempting to update/deprecate the default view.
    */
  final case object ViewIsDefaultView
      extends BlazegraphViewRejection(s"Cannot perform write operations on the default Blazegraph view.")

  type ViewIsDefaultView = ViewIsDefaultView.type

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends BlazegraphViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  final case class FetchByTagNotSupported(tag: IdSegmentRef.Tag)
      extends BlazegraphViewRejection(
        s"Fetching blazegraph views by tag is no longer supported. Id ${tag.value.asString} and tag ${tag.tag.value}"
      )

  /**
    * Rejection returned when attempting to decode an expanded JsonLD as an BlazegraphViewValue.
    */
  // TODO Remove when the rejection workflow gets refactored / when view endpoints get separated
  final case class BlazegraphDecodingRejection(error: JsonLdRejection) extends BlazegraphViewRejection(error.reason)

  /**
    * Signals a rejection caused by an attempt to create or update a Blazegraph view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission
    *   the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends BlazegraphViewRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when attempting to update a Blazegraph view with a different value type.
    *
    * @param id
    *   the view id
    */
  final case class DifferentBlazegraphViewType(
      id: Iri,
      provided: BlazegraphViewType,
      expected: BlazegraphViewType
  ) extends BlazegraphViewRejection(
        s"Incorrect Blazegraph View '$id' type: '$provided' provided, expected '$expected'."
      )

  /**
    * Rejection returned when one of the provided view references for an AggregateBlazegraphView does not exist or is
    * deprecated.
    *
    * @param views
    *   the offending view reference
    */
  final case class InvalidViewReferences(views: Set[ViewRef])
      extends BlazegraphViewRejection(
        s"At least one view reference does not exist or is deprecated."
      )

  /**
    * Rejection returned when attempting to interact with a blazegraph view providing an id that cannot be resolved to
    * an Iri.
    *
    * @param id
    *   the view identifier
    */
  final case class InvalidBlazegraphViewId(id: String)
      extends BlazegraphViewRejection(s"Blazegraph view identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when a resource id cannot be expanded to [[Iri]].
    *
    * @param id
    *   the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends BlazegraphViewRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Signals a rejection caused when interacting with the blazegraph client
    */
  final case class WrappedBlazegraphClientError(error: SparqlClientError) extends BlazegraphViewRejection(error.reason)

  /**
    * Rejection returned when too many view references are specified on an aggregated view.
    *
    * @param provided
    *   the number of view references specified
    * @param max
    *   the maximum number of aggregated views allowed
    */
  final case class TooManyViewReferences(provided: Int, max: Int)
      extends BlazegraphViewRejection(s"$provided exceeds the maximum allowed number of view references ($max).")

  implicit private[plugins] val blazegraphViewRejectionEncoder: Encoder.AsObject[BlazegraphViewRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case WrappedBlazegraphClientError(rejection) =>
          obj
            .add(keywords.tpe, "SparqlClientError".asJson)
            .add("details", BlazegraphErrorParser.details(rejection).asJson)
        case IncorrectRev(provided, expected)        => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case InvalidViewReferences(views)            => obj.add("views", views.asJson)
        case BlazegraphDecodingRejection(error)      => error.asJsonObject
        case _: ViewNotFound                         => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                       => obj
      }
    }

  implicit final val blazegraphViewRejectionJsonLdEncoder: JsonLdEncoder[BlazegraphViewRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit val blazegraphViewHttpResponseFields: HttpResponseFields[BlazegraphViewRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)             => StatusCodes.NotFound
      case TagNotFound(_)                     => StatusCodes.NotFound
      case ViewNotFound(_, _)                 => StatusCodes.NotFound
      case ResourceAlreadyExists(_, _)        => StatusCodes.Conflict
      case ViewIsDefaultView                  => StatusCodes.Forbidden
      case IncorrectRev(_, _)                 => StatusCodes.Conflict
      case BlazegraphDecodingRejection(error) => error.status
      case _: FetchByTagNotSupported          => StatusCodes.BadRequest
      case _                                  => StatusCodes.BadRequest
    }
}
