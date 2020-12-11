package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotAccessible
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class ResolverContextResolutionSpec extends AnyWordSpecLike with IOValues with TestHelpers with Matchers {

  private val metadataContext = jsonContentOf("/contexts/metadata.json")

  val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.metadata -> metadataContext)

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  private val project        = ProjectRef.unsafe("org", "project")
  private val readPermission = Permission.unsafe("resource/read")

  val fetchAcls: UIO[AclCollection] =
    IO.pure(
      AclCollection(
        AclGen.resourceFor(
          Acl(AclAddress.Project(project), alice -> Set(readPermission))
        )
      )
    )

  private val resourceId = nxv + "id"
  private val context    = ContextValue(nxv + "context")

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
      Json.obj(),
      CompactedJsonLd.empty.copy(ctx = context),
      ExpandedJsonLd.empty
    )
  )

  private val resolver = InProjectResolver(
    nxv + "in-project",
    project,
    InProjectValue(Priority.unsafe(20)),
    Json.obj(),
    Map.empty
  )

  def fetchResource: (ResourceRef, ProjectRef) => IO[ResourceNotFound, ResourceF[Resource]] =
    (r: ResourceRef, p: ProjectRef) =>
      (r, p) match {
        case (Latest(id), `project`) if resourceId == id => IO.pure(resource)
        case _                                           => IO.raiseError(ResourceNotFound(r.original, p))
      }

  val resourceResolution = new ResourceResolution(
    fetchAcls,
    (_: ProjectRef) => IO.pure(List(resolver)),
    (_: IdSegment, projectRef: ProjectRef) => IO.raiseError(ResolverNotFound(nxv + "not-found", projectRef)),
    fetchResource,
    readPermission
  )

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
        .accepted shouldEqual context.contextObj.asJson
    }

    "fail is applying for an unknown resource" in {
      resolverContextResolution(project)
        .resolve(nxv + "xxx")
        .rejectedWith[RemoteContextNotAccessible]
    }
  }

}
