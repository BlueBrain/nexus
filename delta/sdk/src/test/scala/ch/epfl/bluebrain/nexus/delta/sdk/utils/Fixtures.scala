package ch.epfl.bluebrain.nexus.delta.sdk.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.testkit.IOValues

trait Fixtures extends IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl         -> ContextValue.fromFile("contexts/shacl.json").accepted,
      contexts.error         -> ContextValue.fromFile(("contexts/error.json")).accepted,
      contexts.metadata      -> ContextValue.fromFile(("contexts/metadata.json")).accepted,
      contexts.permissions   -> ContextValue.fromFile(("contexts/permissions.json")).accepted,
      contexts.organizations -> ContextValue.fromFile(("contexts/organizations.json")).accepted,
      contexts.resolvers     -> ContextValue.fromFile(("contexts/resolvers.json")).accepted
    )

}
