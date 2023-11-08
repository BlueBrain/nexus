package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SupervisionRoutes.SupervisionBundle
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.baseUriPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.directives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.supervision
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{SupervisedDescription, Supervisor}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName

class SupervisionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    supervised: IO[List[SupervisedDescription]]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck) {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("supervision") {
        extractCaller { implicit caller =>
          get {
            operationName(s"$prefixSegment/supervision/projections") {
              authorizeFor(AclAddress.Root, supervision.read).apply {
                emit(supervised.map(SupervisionBundle))
              }
            }
          }
        }
      }
    }

}

object SupervisionRoutes {

  case class SupervisionBundle(projections: List[SupervisedDescription])

  implicit final val runningProjectionsEncoder: Encoder[SupervisionBundle]       =
    deriveEncoder
  implicit val runningProjectionsJsonLdEncoder: JsonLdEncoder[SupervisionBundle] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.supervision))

  final def apply(
      identities: Identities,
      aclCheck: AclCheck,
      supervisor: Supervisor
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): SupervisionRoutes =
    new SupervisionRoutes(identities, aclCheck, supervisor.getRunningProjections())

}
