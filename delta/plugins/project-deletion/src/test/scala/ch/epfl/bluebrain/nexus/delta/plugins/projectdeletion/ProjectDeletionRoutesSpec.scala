package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.{ProjectDeletionConfig, contexts}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import scala.concurrent.duration.DurationInt

class ProjectDeletionRoutesSpec extends CatsEffectSpec with RouteHelpers {

  implicit private val ordering: JsonKeyOrdering    = JsonKeyOrdering.default()
  implicit private val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
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
