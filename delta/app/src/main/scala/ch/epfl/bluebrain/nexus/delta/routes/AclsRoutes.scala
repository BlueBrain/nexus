package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, MalformedQueryParamRejection, Route}
import cats.implicits.{toBifunctorOps, toFunctorOps}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes.PatchAcl._
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.{CirceUnmarshalling, QueryParamsUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls => aclsPermissions, _}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclAddressFilter, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{encodeResults, searchResultsJsonLdEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Identities}
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.generic.semiauto.deriveDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

import scala.annotation.nowarn

class AclsRoutes(identities: Identities, acls: Acls)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling
    with QueryParamsUnmarshalling {

  private val any = "*"

  private val simultaneousRevAndAncestorsRejection =
    MalformedQueryParamRejection("rev", "rev and ancestors query parameters cannot be present simultaneously")

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
        implicit val subject = caller.subject
        concat(
          // SSE acls
          (pathPrefix("events") & pathEndOrSingleSlash) {
            authorizeFor(AclAddress.Root, events.read).apply {
              operationName(s"$prefixSegment/acls/events") {
                lastEventId { offset =>
                  completeStream(acls.events(offset))
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
                      completeIO(status, acls.replace(Acl(address, values: _*), rev).map(_.void))
                    }
                  },
                  // Append or subtract ACLs
                  (patch & entity(as[PatchAcl]) & authorizeFor(address, aclsPermissions.write)) {
                    case Append(AclValues(values))   =>
                      completeIO(acls.append(Acl(address, values: _*), rev).map(_.void))
                    case Subtract(AclValues(values)) =>
                      completeIO(acls.subtract(Acl(address, values: _*), rev).map(_.void))
                  },
                  // Delete ACLs
                  delete {
                    authorizeFor(address, aclsPermissions.write).apply {
                      completeIO(OK, acls.delete(address, rev).map(_.void))
                    }
                  },
                  (get & parameter("self" ? true)) {
                    case true  =>
                      (parameter("rev".as[Long].?) & parameter("ancestors" ? false)) {
                        case (Some(_), true)    => reject(simultaneousRevAndAncestorsRejection)
                        case (Some(rev), false) =>
                          // Fetch self ACLs without ancestors at specific revision
                          completeIO(
                            acls
                              .fetchSelfAt(address, rev)
                              .map[SearchResults[AclResource]] { acl =>
                                val searchResults = Seq(acl).flatten
                                SearchResults(searchResults.size.toLong, searchResults)
                              }
                              .leftWiden[AclRejection]
                          )
                        case (None, true)       =>
                          // Fetch self ACLs with ancestors
                          completeUnscoredSearch(
                            acls
                              .fetchSelfWithAncestors(address)
                              .map(_.value.values.toSeq)
                          )
                        case (None, false)      =>
                          // Fetch self ACLs without ancestors
                          completeUnscoredSearch(
                            acls
                              .fetchSelf(address)
                              .map(Seq(_).flatten)
                          )
                      }
                    case false =>
                      authorizeFor(address, aclsPermissions.read).apply {
                        (parameter("rev".as[Long].?) & parameter("ancestors" ? false)) {
                          case (Some(_), true)    => reject(simultaneousRevAndAncestorsRejection)
                          case (Some(rev), false) =>
                            // Fetch all ACLs without ancestors at specific revision
                            completeIO(
                              acls
                                .fetchAt(address, rev)
                                .map[SearchResults[AclResource]] { acl =>
                                  val searchResults = Seq(acl).flatten
                                  SearchResults(searchResults.size.toLong, searchResults)
                                }
                                .leftWiden[AclRejection]
                            )
                          case (None, true)       =>
                            // Fetch all ACLs with ancestors
                            completeUnscoredSearch(
                              acls
                                .fetchWithAncestors(address)
                                .map(_.value.values.toSeq)
                            )
                          case (None, false)      =>
                            // Fetch all ACLs without ancestors
                            completeUnscoredSearch(
                              acls
                                .fetch(address)
                                .map(Seq(_).flatten)
                            )
                        }
                      }
                  }
                )
              }
            }
          },
          (get & extractAclAddressFilter) { addressFilter =>
            operationName(s"$prefixSegment/acls${addressFilter.string}") {
              parameter("self" ? true) {
                case true  =>
                  // Filter self ACLs with or without ancestors
                  completeSearch(
                    acls
                      .listSelf(addressFilter)
                      .map(aclCol => SearchResults(aclCol.value.size.toLong, aclCol.value.values.toSeq))
                  )
                case false =>
                  // Filter all ACLs with or without ancestors
                  completeSearch(
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

}

object AclsRoutes {

  final private case class IdentityPermissions(identity: Identity, permissions: Set[Permission])

  final private[routes] case class AclValues(value: Seq[(Identity, Set[Permission])])
  private[routes] object AclValues {
    implicit private val identityPermsDecoder: Decoder[IdentityPermissions] = deriveDecoder[IdentityPermissions]

    implicit val aclValuesDecoder: Decoder[AclValues] =
      Decoder
        .decodeSeq[IdentityPermissions]
        .map(seq => AclValues(seq.map(value => value.identity -> value.permissions)))
  }

  final private[routes] case class ReplaceAcl(acl: AclValues)
  private[routes] object ReplaceAcl {
    implicit val aclReplaceDecoder: Decoder[ReplaceAcl] = deriveDecoder[ReplaceAcl]
  }

  sealed private[routes] trait PatchAcl extends Product with Serializable
  private[routes] object PatchAcl {
    final case class Subtract(acl: AclValues) extends PatchAcl
    final case class Append(acl: AclValues)   extends PatchAcl

    @nowarn("cat=unused")
    implicit private val config: Configuration      = Configuration.default.withDiscriminator(keywords.tpe)
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
