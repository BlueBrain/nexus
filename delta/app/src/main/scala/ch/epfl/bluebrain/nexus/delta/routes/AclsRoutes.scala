package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, MalformedQueryParamRejection, Route}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes.PatchAcl._
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls => aclsPermissions, _}
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclAddressFilter, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{encodeResults, searchResultsJsonLdEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Identities}
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

import scala.annotation.nowarn

class AclsRoutes(identities: Identities, acls: Acls)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with QueryParamsUnmarshalling {

  private val any = "*"

  private val simultaneousRevAndAncestorsRejection =
    MalformedQueryParamRejection("rev", "rev and ancestors query parameters cannot be present simultaneously.")

  import baseUri.prefixSegment
  implicit val aclContext: ContextValue = Acl.context

  implicit val searchEncoder: SearchEncoder[AclResource] = encodeResults(_ => None)

  private def extractAclAddress: Directive1[AclAddress] =
    extractUnmatchedPath.flatMap {
      case SingleSlash                                                                                            => provide(AclAddress.Root)
      case Path.Empty                                                                                             => provide(AclAddress.Root)
      case Path.Slash(Path.Segment(org, Path.Empty)) if org != any                                                => provide(Organization(Label.unsafe(org)))
      case Path.Slash(Path.Segment(org, Path.Slash(Path.Segment(proj, Path.Empty)))) if org != any && proj != any =>
        (for {
          orgLabel  <- Label(org)
          projLabel <- Label(proj)
        } yield Project(orgLabel, projLabel)).fold(
          err => reject(validationRejection(err.getMessage)),
          provide(_)
        )
      case _                                                                                                      => reject

    }

  private def extractAclAddressFilter: Directive1[AclAddressFilter] =
    (extractUnmatchedPath & parameter("ancestors" ? false)).tflatMap { case (path, ancestors) =>
      path match {
        case Path.Slash(Path.Segment(`any`, Path.Empty))                                  => provide(AnyOrganization(ancestors))
        case Path.Slash(Path.Segment(`any`, Path.Slash(Path.Segment(`any`, Path.Empty)))) =>
          provide(AnyOrganizationAnyProject(ancestors))
        case Path.Slash(Path.Segment(org, Path.Slash(Path.Segment(`any`, Path.Empty))))   =>
          Label(org).fold(
            err => reject(validationRejection(err.getMessage)),
            label => provide[AclAddressFilter](AnyProject(label, ancestors))
          )
        case _                                                                            => reject
      }
    }

  def routes: Route = baseUriPrefix(baseUri.prefix) {
    pathPrefix("acls") {
      extractCaller { implicit caller =>
        concat(
          // SSE ACLs
          (pathPrefix("events") & pathEndOrSingleSlash) {
            authorizeFor(AclAddress.Root, events.read).apply {
              operationName(s"$prefixSegment/acls/events") {
                lastEventId { offset =>
                  emit(acls.events(offset))
                }
              }
            }
          },
          extractAclAddress { address =>
            parameter("rev" ? 0L) { rev =>
              operationName(s"$prefixSegment/acls${address.string}") {
                concat(
                  // Replace ACLs
                  (put & entity(as[ReplaceAcl])) { case ReplaceAcl(AclValues(values)) =>
                    authorizeFor(address, aclsPermissions.write).apply {
                      val status = if (rev == 0L) Created else OK
                      emit(status, acls.replace(Acl(address, values: _*), rev).mapValue(_.metadata))
                    }
                  },
                  // Append or subtract ACLs
                  (patch & entity(as[PatchAcl]) & authorizeFor(address, aclsPermissions.write)) {
                    case Append(AclValues(values))   =>
                      emit(acls.append(Acl(address, values: _*), rev).mapValue(_.metadata))
                    case Subtract(AclValues(values)) =>
                      emit(acls.subtract(Acl(address, values: _*), rev).mapValue(_.metadata))
                  },
                  // Delete ACLs
                  delete {
                    authorizeFor(address, aclsPermissions.write).apply {
                      emit(OK, acls.delete(address, rev).mapValue(_.metadata))
                    }
                  },
                  // Fetch ACLs
                  (get & parameter("self" ? true)) {
                    case true  =>
                      (parameter("rev".as[Long].?) & parameter("ancestors" ? false)) {
                        case (Some(_), true)    => emit(simultaneousRevAndAncestorsRejection)
                        case (Some(rev), false) =>
                          // Fetch self ACLs without ancestors at specific revision
                          emit(notFoundToNone(acls.fetchSelfAt(address, rev)).map(searchResults(_)))
                        case (None, true)       =>
                          // Fetch self ACLs with ancestors
                          emit(acls.fetchSelfWithAncestors(address).map(col => searchResults(col.value.values)))
                        case (None, false)      =>
                          // Fetch self ACLs without ancestors
                          emit(notFoundToNone(acls.fetchSelf(address)).map(searchResults(_)))
                      }
                    case false =>
                      authorizeFor(address, aclsPermissions.read).apply {
                        (parameter("rev".as[Long].?) & parameter("ancestors" ? false)) {
                          case (Some(_), true)    => reject(simultaneousRevAndAncestorsRejection)
                          case (Some(rev), false) =>
                            // Fetch all ACLs without ancestors at specific revision
                            emit(notFoundToNone(acls.fetchAt(address, rev)).map(searchResults(_)))
                          case (None, true)       =>
                            // Fetch all ACLs with ancestors
                            emit(acls.fetchWithAncestors(address).map(col => searchResults(col.value.values)))
                          case (None, false)      =>
                            // Fetch all ACLs without ancestors
                            emit(notFoundToNone(acls.fetch(address)).map(searchResults(_)))
                        }
                      }
                  }
                )
              }
            }
          },
          // Filter ACLs
          (get & extractAclAddressFilter) { addressFilter =>
            operationName(s"$prefixSegment/acls${addressFilter.string}") {
              parameter("self" ? true) {
                case true  =>
                  // Filter self ACLs with or without ancestors
                  emit(
                    acls
                      .listSelf(addressFilter)
                      .map(aclCol => SearchResults(aclCol.value.size.toLong, aclCol.value.values.toSeq))
                  )
                case false =>
                  // Filter all ACLs with or without ancestors
                  emit(
                    acls
                      .list(addressFilter)
                      .map { aclCol =>
                        val filtered = aclCol.filterByPermission(caller.identities, aclsPermissions.read)
                        SearchResults(filtered.value.size.toLong, filtered.value.values.toSeq)
                      }
                  )
              }
            }
          }
        )
      }
    }
  }

  private def notFoundToNone(result: IO[AclRejection, AclResource]): IO[AclRejection, Option[AclResource]] =
    result.attempt.flatMap {
      case Right(resource)      => IO.pure(Some(resource))
      case Left(AclNotFound(_)) => IO.pure(None)
      case Left(rejection)      => IO.raiseError(rejection)
    }

  private def searchResults(iter: Iterable[AclResource]): SearchResults[AclResource] = {
    val vector = iter.toVector
    SearchResults(vector.length.toLong, vector)
  }
}

object AclsRoutes {

  @nowarn("cat=unused")
  implicit private val config: Configuration =
    Configuration.default.withStrictDecoding.withDiscriminator(keywords.tpe)

  final private case class IdentityPermissions(identity: Identity, permissions: Set[Permission])

  final private[routes] case class AclValues(value: Seq[(Identity, Set[Permission])])
  private[routes] object AclValues {
    implicit private val identityPermsDecoder: Decoder[IdentityPermissions] =
      deriveConfiguredDecoder[IdentityPermissions]

    implicit val aclValuesDecoder: Decoder[AclValues] =
      Decoder
        .decodeSeq[IdentityPermissions]
        .map(seq => AclValues(seq.map(value => value.identity -> value.permissions)))
  }

  final private[routes] case class ReplaceAcl(acl: AclValues)
  private[routes] object ReplaceAcl {
    implicit val aclReplaceDecoder: Decoder[ReplaceAcl] = deriveConfiguredDecoder[ReplaceAcl]
  }

  sealed private[routes] trait PatchAcl extends Product with Serializable
  private[routes] object PatchAcl {
    final case class Subtract(acl: AclValues) extends PatchAcl
    final case class Append(acl: AclValues)   extends PatchAcl

    implicit val aclPatchDecoder: Decoder[PatchAcl] = deriveConfiguredDecoder[PatchAcl]
  }

  /**
    * @return the [[Route]] for ACLs
    */
  def apply(identities: Identities, acls: Acls)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): AclsRoutes = new AclsRoutes(identities, acls)

}
