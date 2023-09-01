package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext.StaticContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotAccessible
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceResolutionGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution.NexusContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class ResolverContextResolutionSpec extends AnyWordSpecLike with IOValues with TestHelpers with Matchers {

  private val metadataContext = jsonContentOf("/contexts/metadata.json").topContextValueOrEmpty

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
    rev = 5,
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
      Tags.empty,
      Latest(nxv + "schema"),
      Json.obj(keywords.context -> context),
      CompactedJsonLd.empty,
      ExpandedJsonLd.empty
    )
  )

  def fetchResource: (ResourceRef, ProjectRef) => FetchResource[Resource] = { (r: ResourceRef, p: ProjectRef) =>
    (r, p) match {
      case (Latest(id), `project`) if resourceId == id => IO.some(resource)
      case _                                           => IO.none
    }
  }

  private val resourceResolution = ResourceResolutionGen.singleInProject(project, fetchResource)

  private val resolverContextResolution = ResolverContextResolution(rcr, resourceResolution)

  "Resolving contexts" should {

    "resolve correctly static contexts" in {
      val expected = StaticContext(contexts.metadata, metadataContext)
      resolverContextResolution(project).resolve(contexts.metadata).accepted shouldEqual expected
    }

    "resolve correctly a resource context" in {
      val expected = NexusContext(resourceId, project, 5, ContextValue(context))
      resolverContextResolution(project).resolve(resourceId).accepted shouldEqual expected
    }

    "fail is applying for an unknown resource" in {
      resolverContextResolution(project)
        .resolve(nxv + "xxx")
        .rejectedWith[RemoteContextNotAccessible]
    }
  }

}
