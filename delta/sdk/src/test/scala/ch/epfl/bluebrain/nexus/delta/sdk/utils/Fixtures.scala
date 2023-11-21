package ch.epfl.bluebrain.nexus.delta.sdk.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ClasspathResources

trait Fixtures {
  self: ClasspathResources =>
  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json"),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json"),
      contexts.error           -> ContextValue.fromFile("contexts/error.json"),
      contexts.metadata        -> ContextValue.fromFile("contexts/metadata.json"),
      contexts.permissions     -> ContextValue.fromFile("contexts/permissions.json"),
      contexts.organizations   -> ContextValue.fromFile("contexts/organizations.json"),
      contexts.resolvers       -> ContextValue.fromFile("contexts/resolvers.json")
    )

}
