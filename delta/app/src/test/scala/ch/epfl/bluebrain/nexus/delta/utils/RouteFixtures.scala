package ch.epfl.bluebrain.nexus.delta.utils

import akka.http.scaladsl.server.RejectionHandler
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import monix.execution.Scheduler

trait RouteFixtures extends TestHelpers {

  implicit val rcr: RemoteContextResolutionDummy =
    RemoteContextResolutionDummy(
      contexts.resource      -> jsonContentOf("contexts/resource.json"),
      contexts.error         -> jsonContentOf("contexts/error.json"),
      contexts.organizations -> jsonContentOf("contexts/organizations.json"),
      contexts.identities    -> jsonContentOf("contexts/identities.json"),
      contexts.permissions   -> jsonContentOf("contexts/permissions.json"),
      contexts.realms        -> jsonContentOf("contexts/realms.json")
    )

  implicit val ordering: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val s: Scheduler                       = Scheduler.global
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply

  val realm: Label = Label.unsafe("wonderland")
  val alice: User  = User("alice", realm)

  def resourceUnit(
      id: Iri,
      tpe: String,
      schema: Iri,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "resource-unit.json",
      Map(
        "id"         -> id,
        "type"       -> tpe,
        "schema"     -> schema,
        "deprecated" -> deprecated,
        "rev"        -> rev,
        "createdBy"  -> createdBy.id,
        "updatedBy"  -> updatedBy.id
      )
    )

}
