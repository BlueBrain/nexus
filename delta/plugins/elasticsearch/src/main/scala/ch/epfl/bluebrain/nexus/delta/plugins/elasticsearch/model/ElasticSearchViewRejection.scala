package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

/**
  * Enumeration of ElasticSearch view rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ElasticSearchViewRejection(val reason: String) extends Product with Serializable

object ElasticSearchViewRejection {

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ElasticSearchViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends ElasticSearchViewRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a view with an id that already exists.
    *
    * @param id the view id
    */
  final case class ViewAlreadyExists(id: Iri, project: ProjectRef)
      extends ElasticSearchViewRejection(s"ElasticSearch view '$id' already exists in project '$project'.")

  /**
    * Rejection returned when a view that doesn't exist.
    *
    * @param id the view id
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends ElasticSearchViewRejection(s"ElasticSearch view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id the view id
    */
  final case class ViewIsDeprecated(id: Iri)
      extends ElasticSearchViewRejection(s"ElasticSearch view '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends ElasticSearchViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection)
      extends ElasticSearchViewRejection(rejection.reason)

  /**
    * Signals a rejection caused by an attempt to create or update an ElasticSearch view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends ElasticSearchViewRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when view of type ''expected'' was desired but a view ''provided'' was provided instead.
    * This can happen during update of a view when attempting to change the type or during fetch of a particular type of view
    *
    * @param id the view id
    */
  final case class DifferentElasticSearchViewType(
      id: Iri,
      provided: ElasticSearchViewType,
      expected: ElasticSearchViewType
  ) extends ElasticSearchViewRejection(
        s"Incorrect ElasticSearch view '$id' type: '$provided' provided, expected '$expected'."
      )

  /**
    * Rejection returned when the provided ElasticSearch mapping for an IndexingElasticSearchView is invalid.
    */
  final case class InvalidElasticSearchIndexPayload(details: Option[Json])
      extends ElasticSearchViewRejection("The provided ElasticSearch mapping value is invalid.")

  /**
    * Rejection returned when one of the provided view references for an AggregateElasticSearchView does not exist or
    * is deprecated.
    *
    * @param view the offending view reference
    */
  final case class InvalidViewReference(view: ViewRef)
      extends ElasticSearchViewRejection(
        s"The ElasticSearch view reference with id '${view.viewId}' in project '${view.project}' does not exist or is deprecated."
      )

  /**
    * Rejection returned when the returned state is the initial state after a successful command evaluation.
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a
    * non initial state.
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends ElasticSearchViewRejection(s"Unexpected initial state for ElasticSearchView '$id' of project '$project'.")

  /**
    * Rejection returned when attempting to create an ElasticSearchView where the passed id does not match the id on the
    * source json document.
    *
    * @param id       the view identifier
    * @param sourceId the view identifier in the source json document
    */
  final case class UnexpectedElasticSearchViewId(id: Iri, sourceId: Iri)
      extends ElasticSearchViewRejection(
        s"The provided ElasticSearch view '$id' does not match the id '$sourceId' in the source document."
      )

  /**
    * Rejection returned when attempting to interact with an ElasticSearchView while providing an id that cannot be
    * resolved to an Iri.
    *
    * @param id the view identifier
    */
  final case class InvalidElasticSearchViewId(id: String)
      extends ElasticSearchViewRejection(s"ElasticSearch view identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection when attempting to decode an expanded JsonLD as an ElasticSearchViewValue.
    *
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends ElasticSearchViewRejection(error.getMessage)

  /**
    * Signals an error converting the source Json document to a JsonLD document.
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends ElasticSearchViewRejection(
        s"The provided ElasticSearch view JSON document ${id.fold("")(id => s"with id '$id'")} cannot be interpreted as a JSON-LD document."
      )

  /**
    * Rejection returned when attempting to query an elasticsearchview
    * and the caller does not have the right permissions defined in the view.
    */
  final case object AuthorizationFailed extends ElasticSearchViewRejection(ServiceError.AuthorizationFailed.reason)

  type AuthorizationFailed = AuthorizationFailed.type

  /**
    * Signals a rejection caused when interacting with the elasticserch client
    */
  final case class WrappedElasticSearchClientError(error: HttpClientError)
      extends ElasticSearchViewRejection(error.reason)

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id        the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends ElasticSearchViewRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  implicit final val projectToElasticSearchRejectionMapper: Mapper[ProjectRejection, ElasticSearchViewRejection] =
    (value: ProjectRejection) => WrappedProjectRejection(value)

  implicit final val jsonLdRejectionMapper: Mapper[JsonLdRejection, ElasticSearchViewRejection] = {
    case JsonLdRejection.UnexpectedId(id, sourceId)        => UnexpectedElasticSearchViewId(id, sourceId)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit val elasticSearchRejectionEncoder: Encoder.AsObject[ElasticSearchViewRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedElasticSearchClientError(rejection) =>
          obj.add("@type", "ElasticSearchClientError".asJson).addIfExists("details", rejection.jsonBody)
        case WrappedProjectRejection(rejection)         => rejection.asJsonObject
        case InvalidJsonLdFormat(_, details)            => obj.add("details", details.reason.asJson)
        case IncorrectRev(provided, expected)           => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case InvalidElasticSearchIndexPayload(details)  => obj.addIfExists("details", details)
        case _                                          => obj
      }
    }

  implicit final val viewRejectionJsonLdEncoder: JsonLdEncoder[ElasticSearchViewRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

}
