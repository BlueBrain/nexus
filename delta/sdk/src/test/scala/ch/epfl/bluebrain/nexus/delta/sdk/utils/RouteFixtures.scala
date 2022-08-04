package ch.epfl.bluebrain.nexus.delta.sdk.utils

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.execution.Scheduler

trait RouteFixtures extends TestHelpers with IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit def rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.acls                  -> ContextValue.fromFile("contexts/acls.json").accepted,
      contexts.aclsMetadata          -> ContextValue.fromFile("contexts/acls-metadata.json").accepted,
      contexts.metadata              -> ContextValue.fromFile("contexts/metadata.json").accepted,
      contexts.error                 -> ContextValue.fromFile("contexts/error.json").accepted,
      contexts.organizations         -> ContextValue.fromFile("contexts/organizations.json").accepted,
      contexts.organizationsMetadata -> ContextValue.fromFile("contexts/organizations-metadata.json").accepted,
      contexts.identities            -> ContextValue.fromFile("contexts/identities.json").accepted,
      contexts.permissions           -> ContextValue.fromFile("contexts/permissions.json").accepted,
      contexts.permissionsMetadata   -> ContextValue.fromFile("contexts/permissions-metadata.json").accepted,
      contexts.projects              -> ContextValue.fromFile("contexts/projects.json").accepted,
      contexts.projectsMetadata      -> ContextValue.fromFile("contexts/projects-metadata.json").accepted,
      contexts.realms                -> ContextValue.fromFile("contexts/realms.json").accepted,
      contexts.realmsMetadata        -> ContextValue.fromFile("contexts/realms-metadata.json").accepted,
      contexts.resolvers             -> ContextValue.fromFile("contexts/resolvers.json").accepted,
      contexts.resolversMetadata     -> ContextValue.fromFile("contexts/resolvers-metadata.json").accepted,
      contexts.search                -> ContextValue.fromFile("contexts/search.json").accepted,
      contexts.shacl                 -> ContextValue.fromFile("contexts/shacl.json").accepted,
      contexts.schemasMetadata       -> ContextValue.fromFile("contexts/schemas-metadata.json").accepted,
      contexts.statistics            -> ContextValue.fromFile("contexts/statistics.json").accepted,
      contexts.tags                  -> ContextValue.fromFile("contexts/tags.json").accepted,
      contexts.version               -> ContextValue.fromFile("/contexts/version.json").accepted,
      contexts.quotas                -> ContextValue.fromFile("/contexts/quotas.json").accepted
    )

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val f: FusionConfig                    = FusionConfig(Uri("https://bbp.epfl.ch/nexus/web/"), enableRedirects = true)
  implicit val s: Scheduler                       = Scheduler.global
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val realm: Label = Label.unsafe("wonderland")
  val alice: User  = User("alice", realm)
  val bob: User    = User("bob", realm)

  def lastSegment(iri: Iri): String =
    iri.toString.substring(iri.toString.lastIndexOf("/") + 1)
}
