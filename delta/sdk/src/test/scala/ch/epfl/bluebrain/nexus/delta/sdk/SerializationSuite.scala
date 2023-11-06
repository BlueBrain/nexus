package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.mu.{EitherAssertions, JsonAssertions}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.parser._
import io.circe.{Json, JsonObject}
import munit.{Assertions, FunSuite, Location}

import scala.collection.immutable.VectorMap

abstract class SerializationSuite
    extends FunSuite
    with Assertions
    with EitherAssertions
    with CirceLiteral
    with JsonAssertions
    with TestHelpers {

  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json").unsafeRunSync(),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json").unsafeRunSync()
    )

  def loadEvents[E](module: String, eventsToFile: (E, String)*): Map[E, (Json, JsonObject)] =
    eventsToFile.foldLeft(VectorMap.empty[E, (Json, JsonObject)]) { case (acc, (event, fileName)) =>
      acc + (event -> loadEvents(module, fileName))
    }

  def loadEvents(module: String, fileName: String): (Json, JsonObject) =
    (jsonContentOf(s"/$module/database/$fileName"), jsonObjectContentOf(s"/$module/sse/$fileName"))

  private def generateOutput[Id, Value](serializer: Serializer[Id, Value], obtained: Value) =
    parse(serializer.printer.print(serializer.codec(obtained)))
      .getOrElse(fail(s"$obtained could not be parsed back as a json"))

  def assertOutput[Id, Value](serializer: Serializer[Id, Value], obtained: Value, expected: Json)(implicit
      loc: Location
  ): Unit = {
    assertEquals(
      generateOutput(serializer, obtained),
      expected
    )

  }

  def assertOutputIgnoreOrder[Id, Value](serializer: Serializer[Id, Value], obtained: Value, expected: Json): Unit =
    generateOutput(serializer, obtained)
      .equalsIgnoreArrayOrder(expected)

}
