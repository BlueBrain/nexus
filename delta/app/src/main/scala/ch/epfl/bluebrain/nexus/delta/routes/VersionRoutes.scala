package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.config.DescriptionConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.VersionRoutes.VersionBundle
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.version
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.{PluginDescription, ServiceDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ComponentDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ServiceDependency}
import io.circe.{Encoder, JsonObject}
import io.circe.syntax._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

import scala.collection.immutable.Iterable

class VersionRoutes(
    identities: Identities,
    acls: Acls,
    main: ServiceDescription,
    plugins: Iterable[PluginDescription],
    dependencies: Iterable[ServiceDependency]
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls) {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("version") {
        extractCaller { implicit caller =>
          (get & pathEndOrSingleSlash) {
            operationName(s"$prefixSegment/version") {
              authorizeFor(AclAddress.Root, version.read).apply {
                emit(UIO.traverse(dependencies)(_.serviceDescription).map(VersionBundle(main, _, plugins)))
              }
            }
          }
        }
      }
    }

}

object VersionRoutes {

  final private[routes] case class VersionBundle(
      main: ServiceDescription,
      dependencies: Iterable[ServiceDescription],
      plugins: Iterable[PluginDescription]
  )

  private[routes] object VersionBundle {
    private def toMap(values: Iterable[ComponentDescription]): Map[String, String] =
      values.map(desc => desc.name.value -> desc.version).toMap

    implicit private val versionBundleEncoder: Encoder.AsObject[VersionBundle]     =
      Encoder.encodeJsonObject.contramapObject { case VersionBundle(main, dependencies, plugins) =>
        JsonObject(
          main.name.value -> main.version.asJson,
          "dependencies"  -> toMap(dependencies).asJson,
          "plugins"       -> toMap(plugins).asJson
        )
      }

    implicit val versionBundleJsonLdEncoder: JsonLdEncoder[VersionBundle] =
      JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.version))
  }

  /**
    * Constructs a [[VersionRoutes]]
    */
  final def apply(
      identities: Identities,
      acls: Acls,
      plugins: Iterable[PluginDescription],
      depdendencies: Iterable[ServiceDependency],
      cfg: DescriptionConfig
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): VersionRoutes =
    new VersionRoutes(identities, acls, ServiceDescription(cfg.name, cfg.version), plugins, depdendencies)
}
