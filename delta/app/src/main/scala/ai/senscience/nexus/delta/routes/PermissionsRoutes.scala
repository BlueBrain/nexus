package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.PermissionsRoutes.PatchPermissions
import ai.senscience.nexus.delta.routes.PermissionsRoutes.PatchPermissions.{Append, Replace, Subtract}
import akka.http.scaladsl.server.{MalformedRequestContentRejection, Route}
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.permissions as permissionsPerms
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.{Permission, PermissionsRejection}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import kamon.instrumentation.akka.http.TracingDirectives.operationName

/**
  * The permissions routes.
  *
  * @param identities
  *   the identities operations bundle
  * @param permissions
  *   the permissions operations bundle
  * @param aclCheck
  *   verify the acls for users
  */
final class PermissionsRoutes(identities: Identities, permissions: Permissions, aclCheck: AclCheck)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit private val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.permissionsMetadata))

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("permissions") {
        extractCaller { implicit caller =>
          concat(
            pathEndOrSingleSlash {
              operationName(s"$prefixSegment/permissions") {
                concat(
                  // Fetch permissions
                  get {
                    authorizeFor(AclAddress.Root, permissionsPerms.read).apply {
                      parameter("rev".as[Int].?) {
                        case Some(rev) => emitPermissions(permissions.fetchAt(rev))
                        case None      => emitPermissions(permissions.fetch)
                      }
                    }
                  },
                  // Replace permissions
                  (put & parameter("rev" ? 0)) { rev =>
                    authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                      entity(as[PatchPermissions]) {
                        case Replace(set) => emitVoid(permissions.replace(set, rev))
                        case _            =>
                          reject(
                            malformedContent(s"Value for field '${keywords.tpe}' must be 'Replace' when using 'PUT'.")
                          )
                      }
                    }
                  },
                  // Append or Subtract permissions
                  (patch & parameter("rev" ? 0)) { rev =>
                    authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                      entity(as[PatchPermissions]) {
                        case Append(set)   => emitVoid(permissions.append(set, rev))
                        case Subtract(set) => emitVoid(permissions.subtract(set, rev))
                        case _             =>
                          reject(
                            malformedContent(
                              s"Value for field '${keywords.tpe}' must be 'Append' or 'Subtract' when using 'PATCH'."
                            )
                          )
                      }
                    }
                  },
                  // Delete permissions
                  delete {
                    authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                      parameter("rev".as[Int]) { rev =>
                        emitVoid(permissions.delete(rev))
                      }
                    }
                  }
                )
              }
            }
          )
        }
      }
    }

  private def emitVoid(value: IO[PermissionsResource]) = {
    emit(value.map(_.void).attemptNarrow[PermissionsRejection])
  }

  private def emitPermissions(value: IO[PermissionsResource]) = {
    emit(value.attemptNarrow[PermissionsRejection])
  }

  private def malformedContent(field: String) =
    MalformedRequestContentRejection(field, new IllegalArgumentException())
}

object PermissionsRoutes {

  /**
    * @return
    *   the [[Route]] for the permission resources
    */
  def apply(identities: Identities, permissions: Permissions, aclCheck: AclCheck)(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new PermissionsRoutes(identities, permissions, aclCheck: AclCheck).routes

  sealed private[routes] trait PatchPermissions extends Product with Serializable

  private[routes] object PatchPermissions {

    final case class Append(permissions: Set[Permission])   extends PatchPermissions
    final case class Subtract(permissions: Set[Permission]) extends PatchPermissions
    final case class Replace(permissions: Set[Permission])  extends PatchPermissions

    implicit final private val configuration: Configuration =
      Configuration.default.withStrictDecoding.withDiscriminator(keywords.tpe)

    private val replacedType = Json.obj(keywords.tpe -> "Replace".asJson)

    implicit val patchPermissionsDecoder: Decoder[PatchPermissions] =
      Decoder.instance { hc =>
        deriveConfiguredDecoder[PatchPermissions].decodeJson(replacedType deepMerge hc.value)
      }
  }

}
