package ai.senscience.nexus.delta.plugins.projectdeletion

import ai.senscience.nexus.delta.projectdeletion.ProjectDeletionRoutes
import ai.senscience.nexus.delta.projectdeletion.model.{contexts, ProjectDeletionConfig}
import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import scala.concurrent.duration.DurationInt

class ProjectDeletionRoutesSpec extends CatsEffectSpec with RouteHelpers {

  implicit private val ordering: JsonKeyOrdering    = JsonKeyOrdering.default()
  implicit private val baseUri: BaseUri             = BaseUri.unsafe("http://localhost", "v1")
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixedIO(
    contexts.projectDeletion -> ContextValue.fromFile("contexts/project-deletion.json")
  )

  "A ProjectDeletionRoutes" should {
    val config     = ProjectDeletionConfig(
      idleInterval = 10.minutes,
      idleCheckPeriod = 5.seconds,
      deleteDeprecatedProjects = true,
      includedProjects = List("some.+".r),
      excludedProjects = List(".+".r)
    )
    val routes     = new ProjectDeletionRoutes(config)
    val configJson = jsonContentOf("project-deletion-config.json")
    "return the project deletion configuration" in {
      Get("/v1/project-deletion/config") ~> routes.routes ~> check {
        status shouldEqual StatusCodes.OK
        contentType.mediaType shouldEqual RdfMediaTypes.`application/ld+json`
        response.asJson shouldEqual configJson
      }
    }
  }

}
