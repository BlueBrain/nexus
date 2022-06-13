package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.model.{HttpHeader, StatusCode, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.{AuthorizationFailed, IndexingFailed, ScopeInitializationFailed}
import ch.epfl.bluebrain.nexus.delta.sdk.error.{IdentityError, ServiceError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

/**
  * Typeclass definition for ''A''s from which the HttpHeaders and StatusCode can be ontained.
  *
  * @tparam A
  *   generic type parameter
  */
trait HttpResponseFields[A] {

  /**
    * Computes a [[StatusCode]] from the argument value.
    *
    * @param value
    *   the input value
    */
  def statusFrom(value: A): StatusCode

  /**
    * Computes a sequence of [[HttpHeader]] from the argument value.
    *
    * @param value
    *   the input value
    */
  def headersFrom(value: A): Seq[HttpHeader]
}

// $COVERAGE-OFF$
object HttpResponseFields {

  /**
    * Constructor helper to build a [[HttpResponseFields]].
    *
    * @param f
    *   function from A to StatusCode
    * @tparam A
    *   type parameter to map to HttpResponseFields
    */
  def apply[A](f: A => StatusCode): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode       = f(value)
      override def headersFrom(value: A): Seq[HttpHeader] = Seq.empty
    }

  /**
    * Constructor helper to build a [[HttpResponseFields]].
    *
    * @param f
    *   function from A to a tuple StatusCode and Seq[HttpHeader]
    * @tparam A
    *   type parameter to map to HttpResponseFields
    */
  def fromStatusAndHeaders[A](f: A => (StatusCode, Seq[HttpHeader])): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode       = f(value)._1
      override def headersFrom(value: A): Seq[HttpHeader] = f(value)._2
    }

  implicit val responseFieldsAcls: HttpResponseFields[AclRejection] =
    HttpResponseFields {
      case AclRejection.AclNotFound(_)            => StatusCodes.NotFound
      case AclRejection.IncorrectRev(_, _, _)     => StatusCodes.Conflict
      case AclRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
      case AclRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
      case AclRejection.AclEvaluationError(_)     => StatusCodes.InternalServerError
      case _                                      => StatusCodes.BadRequest
    }

  implicit val responseFieldsTokenRejection: HttpResponseFields[TokenRejection] =
    HttpResponseFields {
      case TokenRejection.TokenEvaluationError(_) => StatusCodes.InternalServerError
      case _                                      => StatusCodes.Unauthorized
    }

  implicit val responseFieldsIdentities: HttpResponseFields[IdentityError] =
    HttpResponseFields {
      case IdentityError.AuthenticationFailed    => StatusCodes.Unauthorized
      case IdentityError.InvalidToken(rejection) => rejection.status
    }

  implicit val responseFieldsRealms: HttpResponseFields[RealmRejection] =
    HttpResponseFields {
      case RealmRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
      case RealmRejection.RealmNotFound(_)          => StatusCodes.NotFound
      case RealmRejection.IncorrectRev(_, _)        => StatusCodes.Conflict
      case RealmRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
      case RealmRejection.RealmEvaluationError(_)   => StatusCodes.InternalServerError
      case _                                        => StatusCodes.BadRequest
    }

  implicit val responseFieldsProjects: HttpResponseFields[ProjectRejection] =
    HttpResponseFields {
      case ProjectRejection.RevisionNotFound(_, _)            => StatusCodes.NotFound
      case ProjectRejection.ProjectNotFound(_)                => StatusCodes.NotFound
      case ProjectRejection.WrappedQuotaRejection(rej)        => (rej: QuotaRejection).status
      case ProjectRejection.WrappedOrganizationRejection(rej) => rej.status
      case ProjectRejection.ProjectAlreadyExists(_)           => StatusCodes.Conflict
      case ProjectRejection.IncorrectRev(_, _)                => StatusCodes.Conflict
      case ProjectRejection.UnexpectedInitialState(_)         => StatusCodes.InternalServerError
      case ProjectRejection.ProjectEvaluationError(_)         => StatusCodes.InternalServerError
      case _                                                  => StatusCodes.BadRequest
    }

  implicit val responseFieldsResolvers: HttpResponseFields[ResolverRejection] =
    HttpResponseFields {
      case ResolverRejection.RevisionNotFound(_, _)                => StatusCodes.NotFound
      case ResolverRejection.ResolverNotFound(_, _)                => StatusCodes.NotFound
      case ResolverRejection.TagNotFound(_)                        => StatusCodes.NotFound
      case ResolverRejection.InvalidResolution(_, _, _)            => StatusCodes.NotFound
      case ResolverRejection.InvalidResolverResolution(_, _, _, _) => StatusCodes.NotFound
      case ResolverRejection.WrappedProjectRejection(rej)          => rej.status
      case ResolverRejection.WrappedOrganizationRejection(rej)     => rej.status
      case ResolverRejection.ResourceAlreadyExists(_, _)           => StatusCodes.Conflict
      case ResolverRejection.IncorrectRev(_, _)                    => StatusCodes.Conflict
      case ResolverRejection.UnexpectedInitialState(_, _)          => StatusCodes.InternalServerError
      case ResolverRejection.ResolverEvaluationError(_)            => StatusCodes.InternalServerError
      case ResolverRejection.WrappedIndexingActionRejection(_)     => StatusCodes.InternalServerError
      case _                                                       => StatusCodes.BadRequest
    }

  implicit val responseFieldsResources: HttpResponseFields[ResourceRejection] =
    HttpResponseFields {
      case ResourceRejection.RevisionNotFound(_, _)            => StatusCodes.NotFound
      case ResourceRejection.ResourceNotFound(_, _, _)         => StatusCodes.NotFound
      case ResourceRejection.TagNotFound(_)                    => StatusCodes.NotFound
      case ResourceRejection.InvalidSchemaRejection(_, _, _)   => StatusCodes.NotFound
      case ResourceRejection.WrappedOrganizationRejection(rej) => rej.status
      case ResourceRejection.WrappedProjectRejection(rej)      => rej.status
      case ResourceRejection.ResourceAlreadyExists(_, _)       => StatusCodes.Conflict
      case ResourceRejection.IncorrectRev(_, _)                => StatusCodes.Conflict
      case ResourceRejection.UnexpectedInitialState(_)         => StatusCodes.InternalServerError
      case ResourceRejection.ResourceEvaluationError(_)        => StatusCodes.InternalServerError
      case ResourceRejection.WrappedIndexingActionRejection(_) => StatusCodes.InternalServerError
      case _                                                   => StatusCodes.BadRequest
    }

  implicit val responseFieldsSchemas: HttpResponseFields[SchemaRejection] =
    HttpResponseFields {
      case SchemaRejection.RevisionNotFound(_, _)            => StatusCodes.NotFound
      case SchemaRejection.TagNotFound(_)                    => StatusCodes.NotFound
      case SchemaRejection.SchemaNotFound(_, _)              => StatusCodes.NotFound
      case SchemaRejection.ResourceAlreadyExists(_, _)       => StatusCodes.Conflict
      case SchemaRejection.IncorrectRev(_, _)                => StatusCodes.Conflict
      case SchemaRejection.WrappedProjectRejection(rej)      => rej.status
      case SchemaRejection.WrappedOrganizationRejection(rej) => rej.status
      case SchemaRejection.SchemaEvaluationError(_)          => StatusCodes.InternalServerError
      case SchemaRejection.UnexpectedInitialState(_)         => StatusCodes.InternalServerError
      case SchemaRejection.WrappedIndexingActionRejection(_) => StatusCodes.InternalServerError
      case _                                                 => StatusCodes.BadRequest
    }

  implicit val responseFieldsQuotas: HttpResponseFields[QuotaRejection] =
    HttpResponseFields {
      case _: QuotaRejection.QuotasDisabled            => StatusCodes.NotFound
      case QuotaRejection.WrappedProjectRejection(rej) => (rej: ProjectRejection).status
      case _: QuotaRejection.QuotaReached              => StatusCodes.Forbidden
    }

  implicit val responseFieldsServiceError: HttpResponseFields[ServiceError] =
    HttpResponseFields {
      case AuthorizationFailed          => StatusCodes.Forbidden
      case ScopeInitializationFailed(_) => StatusCodes.InternalServerError
      case IndexingFailed(_, _)         => StatusCodes.InternalServerError
    }

  implicit val responseFieldsUnit: HttpResponseFields[Unit] =
    HttpResponseFields { _ => StatusCodes.OK }
}
// $COVERAGE-ON$
