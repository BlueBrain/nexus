package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClassUtils, ClasspathResourceError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.{EvaluationError, EvaluationFailure, EvaluationTimeout}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

import scala.reflect.ClassTag

sealed abstract class BlazegraphViewRejection(val reason: String) extends Product with Serializable

object BlazegraphViewRejection {

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends BlazegraphViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends BlazegraphViewRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a view with an id that already exists.
    *
    * @param id      the view id
    * @param project the project it belongs to
    */
  final case class ViewAlreadyExists(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Blazegraph view '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update a view that doesn't exist.
    *
    * @param id the view id
    * @param project the project it belongs to
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Blazegraph view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id the view id
    */
  final case class ViewIsDeprecated(id: Iri) extends BlazegraphViewRejection(s"Blazegraph view '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends BlazegraphViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection)
      extends BlazegraphViewRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends BlazegraphViewRejection(rejection.reason)

  /**
    * Rejection when attempting to decode an expanded JsonLD as an BlazegraphViewValue.
    *
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends BlazegraphViewRejection(error.getMessage)

  /**
    * Signals an error converting the source Json document to a JsonLD document.
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends BlazegraphViewRejection(
        s"The provided Blazegraph view JSON document ${id.fold("")(id => s"with id '$id' ")}cannot be interpreted as a JSON-LD document."
      )

  /**
    * Rejection returned when attempting to create an BlazegraphView where the passed id does not match the id on the
    * source json document.
    *
    * @param id       the view identifier
    * @param sourceId the view identifier in the source json document
    */
  final case class UnexpectedBlazegraphViewId(id: Iri, sourceId: Iri)
      extends BlazegraphViewRejection(
        s"The provided Blazegraph view '$id' does not match the id '$sourceId' in the source document."
      )

  /**
    * Signals a rejection caused by an attempt to create or update a Blazegraph view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends BlazegraphViewRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when attempting to update a Blazegraph view with a different value type.
    *
    * @param id the view id
    */
  final case class DifferentBlazegraphViewType(
      id: Iri,
      provided: BlazegraphViewType,
      expected: BlazegraphViewType
  ) extends BlazegraphViewRejection(
        s"Incorrect Blazegraph View '$id' type: '$provided' provided, expected '$expected'."
      )

  /**
    * Rejection returned when one of the provided view references for an AggregateBlazegraphView does not exist or
    * is deprecated.
    *
    * @param view the offending view reference
    */
  final case class InvalidViewReference(view: ViewRef)
      extends BlazegraphViewRejection(
        s"The Blazegraph view reference with id '${view.viewId}' in project '${view.project}' does not exist or is deprecated."
      )

  /**
    * Rejection returned when the returned state is the initial state after a BlazegraphViews.evaluation plus a BlazegraphViews.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Unexpected initial state for Blazegraph view '$id' of project '$project'.")

  /**
    * Rejection returned when attempting to interact with a blazegraph view providing an id that cannot be resolved to an Iri.
    *
    * @param id the view identifier
    */
  final case class InvalidBlazegraphViewId(id: String)
      extends BlazegraphViewRejection(s"Blazegraph view identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when a resource [[IdSegment]] cannot be expanded to [[Iri]].
    *
    * @param id the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends BlazegraphViewRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to query a BlazegraphView
    * and the caller does not have the right permissions defined in the view.
    */
  final case object AuthorizationFailed extends BlazegraphViewRejection(ServiceError.AuthorizationFailed.reason)
  type AuthorizationFailed = AuthorizationFailed.type

  /**
    * Signals a rejection caused when interacting with the blazegraph client
    */
  final case class WrappedBlazegraphClientError(error: SparqlClientError) extends BlazegraphViewRejection(error.reason)

  /**
    * Signals a rejection caused by a failure to load resource from classpath
    */
  final case class WrappedClasspathResourceError(error: ClasspathResourceError)
      extends BlazegraphViewRejection(error.toString)

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class BlazegraphViewEvaluationError(err: EvaluationError)
      extends BlazegraphViewRejection("Unexpected evaluation error")

  /**
    * Rejection returned when too many view references are specified on an aggregated view.
    *
    * @param provided the number of view references specified
    * @param max      the maximum number of aggregated views allowed
    */
  final case class TooManyViewReferences(provided: Int, max: Int)
      extends BlazegraphViewRejection(s"$provided exceeds the maximum allowed number of view references ($max).")

  implicit val blazegraphViewsProjectRejectionMapper: Mapper[ProjectRejection, BlazegraphViewRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val blazegraphViewOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val jsonLdRejectionMapper: Mapper[JsonLdRejection, BlazegraphViewRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedBlazegraphViewId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit private[plugins] def blazegraphViewRejectionEncoder(implicit
      C: ClassTag[BlazegraphViewCommand]
  ): Encoder.AsObject[BlazegraphViewRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case BlazegraphViewEvaluationError(EvaluationFailure(C(cmd), _)) =>
          val reason =
            s"Unexpected failure while evaluating the command '${simpleName(cmd)}' for blazegraph view '${cmd.id}'"
          JsonObject(keywords.tpe -> "BlazegraphViewEvaluationFailure".asJson, "reason" -> reason.asJson)
        case BlazegraphViewEvaluationError(EvaluationTimeout(C(cmd), t)) =>
          val reason =
            s"Timeout while evaluating the command '${simpleName(cmd)}' for blazegraph view '${cmd.id}' after '$t'"
          JsonObject(keywords.tpe -> "BlazegraphViewEvaluationTimeout".asJson, "reason" -> reason.asJson)
        case WrappedOrganizationRejection(rejection)                     => rejection.asJsonObject
        case WrappedProjectRejection(rejection)                          => rejection.asJsonObject
        case WrappedBlazegraphClientError(rejection)                     =>
          obj.add(keywords.tpe, "SparqlClientError".asJson).add("details", rejection.toString().asJson)
        case IncorrectRev(provided, expected)                            => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case InvalidJsonLdFormat(_, ConversionError(details, _))         => obj.add("details", details.asJson)
        case InvalidJsonLdFormat(_, rdf)                                 => obj.add("rdf", rdf.asJson)
        case _                                                           => obj
      }
    }

  implicit final val blazegraphViewRejectionJsonLdEncoder: JsonLdEncoder[BlazegraphViewRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit final val evaluationErrorMapper: Mapper[EvaluationError, BlazegraphViewRejection] =
    BlazegraphViewEvaluationError.apply

  implicit val blazegraphViewHttpResponseFields: HttpResponseFields[BlazegraphViewRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)            => StatusCodes.NotFound
      case TagNotFound(_)                    => StatusCodes.NotFound
      case ViewNotFound(_, _)                => StatusCodes.NotFound
      case ViewAlreadyExists(_, _)           => StatusCodes.Conflict
      case IncorrectRev(_, _)                => StatusCodes.Conflict
      case WrappedProjectRejection(rej)      => rej.status
      case WrappedOrganizationRejection(rej) => rej.status
      case UnexpectedInitialState(_, _)      => StatusCodes.InternalServerError
      case WrappedClasspathResourceError(_)  => StatusCodes.InternalServerError
      case BlazegraphViewEvaluationError(_)  => StatusCodes.InternalServerError
      case AuthorizationFailed               => StatusCodes.Forbidden
      case _                                 => StatusCodes.BadRequest
    }
}
