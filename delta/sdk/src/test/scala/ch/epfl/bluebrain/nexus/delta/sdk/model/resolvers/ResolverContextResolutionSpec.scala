package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotAccessible
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceResolutionGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class ResolverContextResolutionSpec extends AnyWordSpecLike with IOValues with TestHelpers with Matchers {

  private val metadataContext = jsonContentOf("/contexts/metadata.json")

  val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.metadata -> metadataContext)

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  private val project = ProjectRef.unsafe("org", "project")

  private val resourceId = nxv + "id"
  private val context    = (nxv + "context").asJson

  private val resource = ResourceF(
    id = resourceId,
    uris = ResourceUris(Uri("/id")),
    rev = 5L,
    types = Set(nxv + "Resource"),
    deprecated = false,
    createdAt = Instant.now(),
    createdBy = alice,
    updatedAt = Instant.now(),
    updatedBy = alice,
    schema = Latest(schemas + "ResourceExample"),
    value = Resource(
      nxv + "example1",
      project,
      Map.empty,
      Latest(nxv + "schema"),
      Json.obj(keywords.context -> context),
      CompactedJsonLd.empty,
      ExpandedJsonLd.empty
    )
  )

  def fetchResource: (ResourceRef, ProjectRef) => IO[ResourceNotFound, ResourceF[Resource]] =
    (r: ResourceRef, p: ProjectRef) =>
      (r, p) match {
        case (Latest(id), `project`) if resourceId == id => IO.pure(resource)
        case _                                           => IO.raiseError(ResourceNotFound(r.original, p))
      }

  private val resourceResolution = ResourceResolutionGen.singleInProject(project, fetchResource)

  private val resolverContextResolution = ResolverContextResolution(rcr, resourceResolution)

  "Resolving contexts" should {

    "resolve correctly static contexts" in {
      resolverContextResolution(project)
        .resolve(contexts.metadata)
        .accepted shouldEqual metadataContext.topContextValueOrEmpty.contextObj.asJson
    }

    "resolve correctly a resource context" in {
      resolverContextResolution(project)
        .resolve(resourceId)
        .accepted shouldEqual Json.obj(keywords.context -> context)
    }

    "fail is applying for an unknown resource" in {
      resolverContextResolution(project)
        .resolve(nxv + "xxx")
        .rejectedWith[RemoteContextNotAccessible]
    }
  }

}
