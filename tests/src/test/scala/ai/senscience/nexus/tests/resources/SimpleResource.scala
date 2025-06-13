package ai.senscience.nexus.tests.resources

import ai.senscience.nexus.tests.Identity.Authenticated
import ai.senscience.nexus.tests.config.TestsConfig
import ai.senscience.nexus.tests.{HandleBarsFixture, SelfFixture}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.Json

/**
  * Utility methods to load the original payload, the fetch response for the `simple resource` used within the
  * integration tests
  */
object SimpleResource extends HandleBarsFixture with SelfFixture {

  private val loader = ClasspathResourceLoader()

  def fetchResponse(user: Authenticated, project: String, resourceId: String, rev: Int, priority: Int)(implicit
      config: TestsConfig
  ): IO[Json] =
    loader.jsonContentOf(
      "kg/resources/simple-resource-response.json",
      replacements(
        user,
        "priority"   -> priority.toString,
        "rev"        -> rev.toString,
        "self"       -> resourceSelf(project, resourceId),
        "project"    -> project,
        "resourceId" -> resourceId
      )*
    )

  def annotatedResource(user: Authenticated, project: String, resourceId: String, rev: Int, priority: Int)(implicit
      config: TestsConfig
  ): IO[Json] =
    loader.jsonContentOf(
      "kg/resources/simple-resource-with-metadata.json",
      replacements(
        user,
        "priority"   -> priority.toString,
        "rev"        -> rev.toString,
        "self"       -> resourceSelf(project, resourceId),
        "project"    -> project,
        "resourceId" -> resourceId
      )*
    )

  def sourcePayload(id: String, priority: Int): IO[Json] =
    loader.jsonContentOf(
      "kg/resources/simple-resource.json",
      "resourceId" -> id,
      "priority"   -> priority.toString
    )

  def sourcePayload(priority: Int): IO[Json] =
    loader.jsonContentOf(
      "kg/resources/simple-resource.json",
      "priority" -> priority.toString
    )

  def sourcePayloadWithType(resourceType: String, priority: Int): IO[Json] =
    loader.jsonContentOf(
      "kg/resources/simple-resource.json",
      "priority"     -> priority.toString,
      "resourceType" -> resourceType
    )

}
