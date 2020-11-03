package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, MalformedQueryParamRejection, Route}
import cats.implicits.{toBifunctorOps, toFunctorOps}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes.PatchAcl._
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.{CirceUnmarshalling, QueryParamsUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclAddressFilter, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{encodeResults, searchResultsJsonLdEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Identities, Lens}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax.EncoderOps
import io.circe._
import monix.execution.Scheduler

class AclsRoutes(identities: Identities, acls: Acls)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling
    with QueryParamsUnmarshalling {

  import baseUri._

  private val aclsIri = endpoint.toIri / "acls"

  private val any = "*"

  private val simultaneousRevAndAncestorsRejection =
    MalformedQueryParamRejection("rev", "rev and ancestors query parameters cannot be present simultaneously")

  implicit val iriLens: Lens[AclAddress, Iri] = {
    case AclAddress.Root    => aclsIri
    case Organization(org)  => aclsIri / org.value
    case Project(org, proj) => aclsIri / org.value / proj.value
  }

  implicit val aclContext: ContextValue = Acl.context

  implicit val aclResponseEncoder: Encoder.AsObject[AclResource] = Encoder.AsObject
    .instance { r: AclResource =>
      r.copy(id = iriLens.get(r.id)).void.asJsonObject deepMerge r.value.asJsonObject deepMerge JsonObject(
        "_path" -> Json.fromString(r.id.string)
      )
    }
    .mapJsonObject(_.remove("_deprecated"))

  implicit val aclWriteResponseEncoder: Encoder.AsObject[ResourceF[AclAddress, Unit]] = Encoder.AsObject
    .instance { r: ResourceF[AclAddress, Unit] =>
      r.copy(id = iriLens.get(r.id)).void.asJsonObject deepMerge r.value.asJsonObject
    }
    .mapJsonObject(_.remove("_deprecated"))

  implicit val aclWriteFResponseEncoder: JsonLdEncoder[ResourceF[AclAddress, Unit]] = JsonLdEncoder.compactFromCirce(
    (v: ResourceF[AclAddress, Unit]) => iriLens.get(v.id),
    iriContext = contexts.resource
  )

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
            label => provide(AnyProject(label, ancestors).asInstanceOf[AclAddressFilter])
          )
        case _                                                                            => reject
      }
    }

  def routes: Route = baseUriPrefix(baseUri.prefix) {
    pathPrefix("acls") {
      extractCaller { implicit caller =>
        concat(
          extractAclAddress { address =>
            extractSubject { implicit subject =>
              parameter("rev" ? 0L) { rev =>
                concat(
                  (put & entity(as[AclInput])) { aclEntity =>
                    authorizeFor(address, aclsWrite).apply {
                      val status = if (rev == 0L) Created else OK
                      completeIO(status, acls.replace(address, aclEntity.toAcl, rev).map(_.void))
                    }
                  },
                  (patch & entity(as[PatchAcl])) { aclPatch =>
                    authorizeFor(address, aclsWrite).apply {
                      aclPatch match {
                        case AppendAcl(acl)   =>
                          completeIO(acls.append(address, acl, rev).map(_.void))
                        case SubtractAcl(acl) =>
                          completeIO(acls.subtract(address, acl, rev).map(_.void))
                      }
                    }
                  },
                  delete {
                    authorizeFor(address, aclsWrite).apply {
                      completeIO(OK, acls.delete(address, rev).map(_.void))
                    }
                  },
                  (get & parameter("self" ? true)) {
                    case true  =>
                      (parameter("rev".as[Long].?) & parameter("ancestors" ? false)) {
                        case (Some(_), true)    => reject(simultaneousRevAndAncestorsRejection)
                        case (Some(rev), false) =>
                          completeIO(
                            acls
                              .fetchSelfAt(address, rev)
                              .map { acl =>
                                val searchResults = Seq(acl).flatten
                                SearchResults(searchResults.size.toLong, searchResults)
                                  .asInstanceOf[SearchResults[AclResource]]
                              }
                              .leftWiden[AclRejection]
                          )
                        case (None, true)       =>
                          completeUnscoredSearch(
                            acls
                              .fetchSelfWithAncestors(address)
                              .map(_.value.values.toSeq)
                          )
                        case (None, false)      =>
                          completeUnscoredSearch(
                            acls
                              .fetchSelf(address)
                              .map(Seq(_).flatten)
                          )
                      }
                    case false =>
                      authorizeFor(address, aclsRead).apply {
                        (parameter("rev".as[Long].?) & parameter("ancestors" ? false)) {
                          case (Some(_), true)    => reject(simultaneousRevAndAncestorsRejection)
                          case (Some(rev), false) =>
                            completeIO(
                              acls
                                .fetchAt(address, rev)
                                .map { acl =>
                                  val searchResults = Seq(acl).flatten
                                  SearchResults(searchResults.size.toLong, searchResults)
                                    .asInstanceOf[SearchResults[AclResource]]
                                }
                                .leftWiden[AclRejection]
                            )
                          case (None, true)       =>
                            completeUnscoredSearch(
                              acls
                                .fetchWithAncestors(address)
                                .map(_.value.values.toSeq)
                            )
                          case (None, false)      =>
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
            parameter("self" ? true) {
              case true  =>
                completeSearch(
                  acls
                    .listSelf(addressFilter)
                    .map(aclCol => SearchResults(aclCol.value.size.toLong, aclCol.value.values.toSeq))
                )
              case false =>
                completeSearch(
                  acls
                    .list(addressFilter)
                    .map(aclCol => SearchResults(aclCol.value.size.toLong, aclCol.value.values.toSeq))
                )
            }
          }
        )
      }
    }
  }

}

object AclsRoutes {

  type AclResponseResource = ResourceF[Iri, Acl]

  private val aclsRead  = Permission.unsafe("acls/read")
  private val aclsWrite = Permission.unsafe("acls/write")

  final private[routes] case class AclEntry(permissions: Set[Permission], identity: Identity)
  final private[routes] case class AclInput(acl: Seq[AclEntry]) {
    def toAcl: Acl = Acl(acl.map(entry => entry.identity -> entry.permissions): _*)
  }

  private[routes] object AclInput {
    implicit val aclEntryDecoder: Decoder[AclEntry] = deriveDecoder[AclEntry]

    implicit val aclDecoder: Decoder[AclInput] = deriveDecoder[AclInput]

  }

  sealed trait PatchAcl
  object PatchAcl {
    final case class SubtractAcl(acl: Acl) extends PatchAcl
    final case class AppendAcl(acl: Acl)   extends PatchAcl

    implicit val patchAclDecoder: Decoder[PatchAcl] =
      Decoder.instance { hc =>
        for {
          tpe   <- hc.get[String]("@type")
          acl   <- hc.value.as[AclInput]
          patch <- tpe match {
                     case "Append"   => Right(AppendAcl(acl.toAcl))
                     case "Subtract" => Right(SubtractAcl(acl.toAcl))
                     case _          => Left(DecodingFailure("@type field must have Append or Subtract value", hc.history))
                   }
        } yield patch
      }
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
