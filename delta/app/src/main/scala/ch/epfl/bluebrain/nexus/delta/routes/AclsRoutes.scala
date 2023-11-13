package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path._
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, MalformedQueryParamRejection, Route}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes.PatchAcl._
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler.{malformedQueryParamEncoder, malformedQueryParamResponseFields}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{QueryParamsUnmarshalling, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{acls => aclsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import scala.annotation.nowarn

class AclsRoutes(identities: Identities, acls: Acls, aclCheck: AclCheck)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with QueryParamsUnmarshalling {

  private val any = "*"

  private val simultaneousRevAndAncestorsRejection =
    MalformedQueryParamRejection("rev", "rev and ancestors query parameters cannot be present simultaneously.")

  implicit private val aclsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[AclResource]] =
    searchResultsJsonLdEncoder(Acl.context)

  implicit private val malformedQueryParamJsonLdEncoder: JsonLdEncoder[MalformedQueryParamRejection] =
    RdfRejectionHandler.compactFromCirceRejection

  private def extractAclAddress: Directive1[AclAddress] =
    extractUnmatchedPath.flatMap {
      case SingleSlash                                                                                            => provide(AclAddress.Root)
      case Path.Empty                                                                                             => provide(AclAddress.Root)
      case Path.Slash(Path.Segment(org, Path.Empty)) if org != any                                                => label(org).map(AclAddress.fromOrg)
      case Path.Slash(Path.Segment(org, Path.Slash(Path.Segment(proj, Path.Empty)))) if org != any && proj != any =>
        for {
          orgLabel  <- label(org)
          projLabel <- label(proj)
        } yield AclAddress.fromProject(ProjectRef(orgLabel, projLabel))
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

  private def emitMetadata(statusCode: StatusCode, io: IO[AclResource]): Route =
    emit(statusCode, io.mapValue(_.metadata).attemptNarrow[AclRejection])

  private def emitMetadata(io: IO[AclResource]): Route = emitMetadata(StatusCodes.OK, io)

  private def emitWithoutAncestors(io: IO[AclResource]): Route = emit {
    io.map(Option(_))
      .recover { case AclNotFound(_) =>
        None
      }
      .map(searchResults(_))
      .attemptNarrow[AclRejection]
  }

  private def emitWithAncestors(io: IO[AclCollection]) =
    emit(io.map { collection => searchResults(collection.value.values) })

  private def searchResults(iter: Iterable[AclResource]): SearchResults[AclResource] = {
    val vector = iter.toVector
    SearchResults(vector.length.toLong, vector)
  }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("acls") {
        extractCaller { implicit caller =>
          concat(
            extractAclAddress { address =>
              parameter("rev" ? 0) { rev =>
                concat(
                  // Replace ACLs
                  (put & entity(as[ReplaceAcl])) { case ReplaceAcl(AclValues(values)) =>
                    authorizeFor(address, aclsPermissions.write).apply {
                      val status = if (rev == 0) Created else OK
                      emitMetadata(status, acls.replace(Acl(address, values: _*), rev))
                    }
                  },
                  // Append or subtract ACLs
                  (patch & entity(as[PatchAcl]) & authorizeFor(address, aclsPermissions.write)) {
                    case Append(AclValues(values))   =>
                      emitMetadata(acls.append(Acl(address, values: _*), rev))
                    case Subtract(AclValues(values)) =>
                      emitMetadata(acls.subtract(Acl(address, values: _*), rev))
                  },
                  // Delete ACLs
                  delete {
                    authorizeFor(address, aclsPermissions.write).apply {
                      emitMetadata(acls.delete(address, rev))
                    }
                  },
                  // Fetch ACLs
                  (get & parameter("self" ? true)) {
                    case true  =>
                      (parameter("rev".as[Int].?) & parameter("ancestors" ? false)) {
                        case (Some(_), true)    => emit(simultaneousRevAndAncestorsRejection)
                        case (Some(rev), false) =>
                          // Fetch self ACLs without ancestors at specific revision
                          emitWithoutAncestors(acls.fetchSelfAt(address, rev))
                        case (None, true)       =>
                          // Fetch self ACLs with ancestors
                          emitWithAncestors(acls.fetchSelfWithAncestors(address))
                        case (None, false)      =>
                          // Fetch self ACLs without ancestors
                          emitWithoutAncestors(acls.fetchSelf(address))
                      }
                    case false =>
                      authorizeFor(address, aclsPermissions.read).apply {
                        (parameter("rev".as[Int].?) & parameter("ancestors" ? false)) {
                          case (Some(_), true)    => reject(simultaneousRevAndAncestorsRejection)
                          case (Some(rev), false) =>
                            // Fetch all ACLs without ancestors at specific revision
                            emitWithoutAncestors(acls.fetchAt(address, rev))
                          case (None, true)       =>
                            // Fetch all ACLs with ancestors
                            emitWithAncestors(acls.fetchWithAncestors(address))
                          case (None, false)      =>
                            // Fetch all ACLs without ancestors
                            emitWithoutAncestors(acls.fetch(address))
                        }
                      }
                  }
                )
              }
            },
            // Filter ACLs
            (get & extractAclAddressFilter) { addressFilter =>
              parameter("self" ? true) {
                case true  =>
                  // Filter self ACLs with or without ancestors
                  emitWithAncestors(acls.listSelf(addressFilter).map(_.removeEmpty()))
                case false =>
                  // Filter all ACLs with or without ancestors
                  emitWithAncestors(
                    acls
                      .list(addressFilter)
                      .map { aclCol =>
                        val accessibleAcls = aclCol.filterByPermission(caller.identities, aclsPermissions.read)
                        val callerAcls     = aclCol.filter(caller.identities)
                        accessibleAcls ++ callerAcls
                      }
                  )
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

    @nowarn("cat=unused")
    implicit private val identityPermsDecoder: Decoder[IdentityPermissions] = {
      implicit val config: Configuration = Configuration.default.withStrictDecoding
      deriveConfiguredDecoder[IdentityPermissions]
    }

    implicit val aclValuesDecoder: Decoder[AclValues] =
      Decoder
        .decodeSeq[IdentityPermissions]
        .map(seq => AclValues(seq.map(value => value.identity -> value.permissions)))
  }

  final private[routes] case class ReplaceAcl(acl: AclValues)
  private[routes] object ReplaceAcl {

    @nowarn("cat=unused")
    implicit val aclReplaceDecoder: Decoder[ReplaceAcl] = {
      implicit val config: Configuration = Configuration.default.withStrictDecoding
      deriveConfiguredDecoder[ReplaceAcl]
    }
  }

  sealed private[routes] trait PatchAcl extends Product with Serializable
  private[routes] object PatchAcl {
    final case class Subtract(acl: AclValues) extends PatchAcl
    final case class Append(acl: AclValues)   extends PatchAcl

    @nowarn("cat=unused")
    implicit val aclPatchDecoder: Decoder[PatchAcl] = {
      implicit val config: Configuration = Configuration.default.withStrictDecoding.withDiscriminator(keywords.tpe)
      deriveConfiguredDecoder[PatchAcl]
    }
  }

  /**
    * @return
    *   the [[Route]] for ACLs
    */
  def apply(identities: Identities, acls: Acls, aclCheck: AclCheck)(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): AclsRoutes = new AclsRoutes(identities, acls, aclCheck)

}
