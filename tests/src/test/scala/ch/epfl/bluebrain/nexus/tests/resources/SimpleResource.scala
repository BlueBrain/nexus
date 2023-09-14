package ch.epfl.bluebrain.nexus.tests.resources

import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import ch.epfl.bluebrain.nexus.tests.Identity.Authenticated
import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import ch.epfl.bluebrain.nexus.tests.{HandleBarsFixture, SelfFixture}
import io.circe.Json

object SimpleResource extends HandleBarsFixture with SelfFixture {

  def fetchResponse(user: Authenticated, project: String, resourceId: String, rev: Int, priority: Int)(implicit
      config: TestsConfig
  ): Json =
    jsonContentOf(
      "/kg/resources/simple-resource-response.json",
      replacements(
        user,
        "priority"   -> priority.toString,
        "rev"        -> rev.toString,
        "self"       -> resourceSelf(project, resourceId),
        "project"    -> s"${config.deltaUri}/projects/$project",
        "resourceId" -> resourceId
      ): _*
    )

  def annotatedResource(user: Authenticated, project: String, resourceId: String, rev: Int, priority: Int)(implicit
      config: TestsConfig
  ): Json =
    jsonContentOf(
      "/kg/resources/simple-resource-with-metadata.json",
      replacements(
        user,
        "priority"   -> priority.toString,
        "rev"        -> rev.toString,
        "self"       -> resourceSelf(project, resourceId),
        "project"    -> s"${config.deltaUri}/projects/$project",
        "resourceId" -> resourceId
      ): _*
    )

  def sourcePayload(id: String, priority: Int): Json =
    jsonContentOf(
      "/kg/resources/simple-resource.json",
      "resourceId" -> id,
      "priority"   -> priority.toString
    )

  def sourcePayload(priority: Int): Json =
    jsonContentOf(
      "/kg/resources/simple-resource.json",
      "priority" -> priority.toString
    )

  def sourcePayloadWithType(resourceType: String, priority: Int): Json =
    jsonContentOf(
      "/kg/resources/simple-resource.json",
      "priority"     -> priority.toString,
      "resourceType" -> resourceType
    )

}
