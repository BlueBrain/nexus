package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherAssertions, JsonAssertions, TestHelpers}
import io.circe.{Json, JsonObject}
import monix.execution.Scheduler
import munit.{Assertions, FunSuite}

abstract class SerializationSuite
    extends FunSuite
    with Assertions
    with EitherAssertions
    with CirceLiteral
    with JsonAssertions
    with TestHelpers {

  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit private val s: Scheduler = Scheduler.global

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json").runSyncUnsafe(),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json").runSyncUnsafe()
    )

  def loadEvents(module: String, fileName: String): (Json, JsonObject) =
    (jsonContentOf(s"/$module/database/$fileName"), jsonObjectContentOf(s"/$module/sse/$fileName"))

}
