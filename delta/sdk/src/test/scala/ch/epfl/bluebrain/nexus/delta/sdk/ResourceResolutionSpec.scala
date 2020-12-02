package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolutionSpec.ResourceExample
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.{ProjectAccessDenied, ResourceNotFound, ResourceTypesDenied}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.{ResolverFailedReport, ResolverSuccessReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{IdentityResolution, Priority, Resolver, ResolverRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.collection.immutable.VectorMap

class ResourceResolutionSpec extends AnyWordSpecLike with Matchers with IOValues with OptionValues with Inspectors {

  private val alice = User("alice", Label.unsafe("wonderland"))
  private val bob   = User("bob", Label.unsafe("wonderland"))

  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  private val project1 = ProjectRef.unsafe("org", "project1")
  private val project2 = ProjectRef.unsafe("org", "project2")
  private val project3 = ProjectRef.unsafe("org", "project3")

  private val readPermission    = Permission.unsafe("my-resource/read")
  private val anotherPermission = Permission.unsafe("xxx/read")

  val fetchAcls: UIO[AclCollection] =
    IO.pure(
      AclCollection(
        AclGen.resourceFor(
          Acl(AclAddress.Project(project1), alice -> Set(readPermission), bob -> Set(readPermission, anotherPermission))
        ),
        AclGen.resourceFor(
          Acl(AclAddress.Project(project2), alice -> Set(anotherPermission), bob -> Set(readPermission))
        ),
        AclGen.resourceFor(Acl(AclAddress.Project(project3), alice -> Set(readPermission)))
      )
    )

  private val resource = ResourceF(
    id = nxv + "example1",
    uris = ResourceUris(Uri("/example1")),
    rev = 5L,
    types = Set(nxv + "ResourceExample", nxv + "ResourceExample2"),
    deprecated = false,
    createdAt = Instant.now(),
    createdBy = alice,
    updatedAt = Instant.now(),
    updatedBy = alice,
    schema = Latest(schemas + "ResourceExample"),
    value = ResourceExample("myResource")
  )

  private val inProjectResolver = InProjectResolver(
    nxv + "in-project-proj-1",
    project1,
    InProjectValue(Priority.unsafe(20)),
    Json.obj(),
    Map.empty
  )

  def crossProjectResolver(
      id: String,
      priority: Int,
      resourceTypes: Set[Iri] = Set.empty,
      projects: NonEmptyList[ProjectRef] = NonEmptyList.of(project1, project2, project3),
      identityResolution: IdentityResolution = UseCurrentCaller
  ): CrossProjectResolver =
    CrossProjectResolver(
      nxv + id,
      project1,
      CrossProjectValue(
        Priority.unsafe(priority),
        resourceTypes,
        projects,
        identityResolution
      ),
      Json.obj(),
      Map.empty
    )

  def listResolvers(resolvers: List[Resolver]): ProjectRef => UIO[List[Resolver]] = (_: ProjectRef) =>
    IO.pure(resolvers)
  private val emptyResolverListQuery                                              = listResolvers(List.empty[Resolver])

  val noResolverFetch: (IdSegment, ProjectRef) => IO[ResolverNotFound, Nothing]                     =
    (_: IdSegment, projectRef: ProjectRef) => IO.raiseError(ResolverNotFound(nxv + "not-found", projectRef))
  def fetchResolver(resolver: Resolver): (IdSegment, ProjectRef) => IO[ResolverRejection, Resolver] =
    (id: IdSegment, projectRef: ProjectRef) =>
      id match {
        case IriSegment(iri) if iri == resolver.id => IO.pure(resolver)
        case _                                     => IO.raiseError(ResolverNotFound(resolver.id, projectRef))
      }

  def fetchResource(
      projectRef: ProjectRef
  ): (ResourceRef, ProjectRef) => IO[ResourceNotFound, ResourceF[ResourceExample]] =
    (_: ResourceRef, p: ProjectRef) =>
      p match {
        case `projectRef` => IO.pure(resource)
        case _            => IO.raiseError(ResourceNotFound(resource.id, p))
      }

  "The Resource resolution" when {

    def singleResolverResolution(resourceProject: ProjectRef, resolver: Resolver) =
      new ResourceResolution(
        fetchAcls,
        emptyResolverListQuery,
        fetchResolver(resolver),
        fetchResource(resourceProject),
        readPermission
      )

    def multipleResolverResolution(resourceProject: ProjectRef, resolvers: Resolver*) =
      new ResourceResolution(
        fetchAcls,
        listResolvers(resolvers.toList),
        noResolverFetch,
        fetchResource(resourceProject),
        readPermission
      )

    "resolving with an in-project resolver" should {
      val resourceResolution = singleResolverResolution(project1, inProjectResolver)

      "fail if the resolver can't be found" in {
        resourceResolution
          .resolve(Latest(resource.id), project1, IriSegment(nxv + "xxx"))
          .rejectedWith[ResolverNotFound]
      }

      "fail if the resource can't be found in the project" in {
        val (report, result) = resourceResolution
          .resolve(
            Latest(resource.id),
            project2,
            IriSegment(inProjectResolver.id)
          )
          .accepted

        report shouldEqual ResolverFailedReport(
          inProjectResolver.id,
          VectorMap(project2 -> ResourceNotFound(resource.id, project2))
        )
        result shouldEqual None
      }

      "be successful if the resource can be fetched" in {
        val (report, result) =
          resourceResolution.resolve(Latest(resource.id), project1, IriSegment(inProjectResolver.id)).accepted

        report shouldEqual ResolverSuccessReport(inProjectResolver.id, VectorMap.empty)
        result.value shouldEqual resource
      }
    }

    "resolving with a cross-project resolver with using current caller resolution" should {
      "succeed at 3rd project" in {
        forAll(
          List(
            crossProjectResolver("use-current", 40, identityResolution = UseCurrentCaller),
            crossProjectResolver(
              "use-current",
              40,
              resourceTypes = resource.types + nxv.Schema,
              identityResolution = UseCurrentCaller
            )
          )
        ) { resolver =>
          val (report, result) = singleResolverResolution(project3, resolver)
            .resolve(Latest(resource.id), project1, IriSegment(resolver.id))
            .accepted

          report shouldEqual ResolverSuccessReport(
            resolver.id,
            VectorMap(
              project1 -> ResourceNotFound(resource.id, project1),
              project2 -> ProjectAccessDenied(project2, UseCurrentCaller)
            )
          )
          result.value shouldEqual resource
        }
      }

      "fail if the caller has no access to the resource project" in {
        val resolver         = crossProjectResolver(
          "use-current",
          40,
          identityResolution = UseCurrentCaller
        )
        val (report, result) = singleResolverResolution(project2, resolver)
          .resolve(Latest(resource.id), project1, IriSegment(resolver.id))
          .accepted

        report shouldEqual ResolverFailedReport(
          resolver.id,
          VectorMap(
            project1 -> ResourceNotFound(resource.id, project1),
            project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
            project3 -> ResourceNotFound(resource.id, project3)
          )
        )
        result shouldEqual None
      }

      "fail if the resource type is not defined in the cross project resolver" in {
        val resolver = crossProjectResolver(
          "use-current",
          40,
          resourceTypes = Set(nxv.Schema),
          identityResolution = UseCurrentCaller
        )

        val resourceResolution = singleResolverResolution(project3, resolver)

        val (report, result) = resourceResolution
          .resolve(Latest(resource.id), project1, IriSegment(resolver.id))
          .accepted

        report shouldEqual ResolverFailedReport(
          resolver.id,
          VectorMap(
            project1 -> ResourceNotFound(resource.id, project1),
            project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
            project3 -> ResourceTypesDenied(project3, resource.types)
          )
        )
        result shouldEqual None
      }

    }

    "resolving with a cross-project resolver with using provided entities resolution" should {
      "succeed at 2nd project" in {
        forAll(
          List(
            crossProjectResolver("provided-entities", 40, identityResolution = ProvidedIdentities(Set(bob))),
            crossProjectResolver(
              "provided-entities",
              40,
              resourceTypes = resource.types + nxv.Schema,
              identityResolution = ProvidedIdentities(Set(bob))
            )
          )
        ) { resolver =>
          val (report, result) = singleResolverResolution(project2, resolver)
            .resolve(Latest(resource.id), project1, IriSegment(resolver.id))
            .accepted

          report shouldEqual ResolverSuccessReport(
            resolver.id,
            VectorMap(
              project1 -> ResourceNotFound(resource.id, project1)
            )
          )
          result.value shouldEqual resource
        }
      }

      "fail if the provided entity has no access to the resource project" in {
        val resolver         = crossProjectResolver(
          "provided-entities",
          40,
          identityResolution = ProvidedIdentities(Set(bob))
        )
        val (report, result) = singleResolverResolution(project3, resolver)
          .resolve(Latest(resource.id), project1, IriSegment(resolver.id))
          .accepted

        report shouldEqual ResolverFailedReport(
          resolver.id,
          VectorMap(
            project1 -> ResourceNotFound(resource.id, project1),
            project2 -> ResourceNotFound(resource.id, project2),
            project3 -> ProjectAccessDenied(project3, ProvidedIdentities(Set(bob)))
          )
        )
        result shouldEqual None
      }
    }

    "resolving with multiple resolvers" should {

      "be successful with the in-project resolver after failing a first time" in {
        val resolution = multipleResolverResolution(
          project1,
          crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
          crossProjectResolver("cross-project-2", priority = 40),
          inProjectResolver
        )

        val (report, result) = resolution.resolveReport(Latest(resource.id), project1).accepted

        report shouldEqual ResourceResolutionReport(
          Vector(
            ResolverFailedReport(
              nxv + "cross-project-1",
              VectorMap(
                project1 -> ResourceTypesDenied(project1, resource.types),
                project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
                project3 -> ResourceNotFound(resource.id, project3)
              )
            ),
            ResolverSuccessReport(
              inProjectResolver.id,
              VectorMap.empty
            )
          )
        )

        result.value shouldEqual resource
      }

      "be successful with the last resolver" in {
        val resolution = multipleResolverResolution(
          project3,
          crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
          crossProjectResolver("cross-project-2", priority = 40, projects = NonEmptyList.of(project3)),
          inProjectResolver
        )

        val (report, result) = resolution.resolveReport(Latest(resource.id), project1).accepted

        report shouldEqual ResourceResolutionReport(
          Vector(
            ResolverFailedReport(
              nxv + "cross-project-1",
              VectorMap(
                project1 -> ResourceNotFound(resource.id, project1),
                project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
                project3 -> ResourceTypesDenied(project3, resource.types)
              )
            ),
            ResolverFailedReport(
              inProjectResolver.id,
              VectorMap(project1 -> ResourceNotFound(resource.id, project1))
            ),
            ResolverSuccessReport(
              nxv + "cross-project-2",
              VectorMap.empty
            )
          )
        )

        result.value shouldEqual resource
      }

      "fail if no resolver matches" in {
        val resolution = multipleResolverResolution(
          project2,
          crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
          crossProjectResolver("cross-project-2", priority = 40, projects = NonEmptyList.of(project3)),
          inProjectResolver
        )

        val (report, result) = resolution.resolveReport(Latest(resource.id), project1).accepted

        report shouldEqual ResourceResolutionReport(
          Vector(
            ResolverFailedReport(
              nxv + "cross-project-1",
              VectorMap(
                project1 -> ResourceNotFound(resource.id, project1),
                project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
                project3 -> ResourceNotFound(resource.id, project3)
              )
            ),
            ResolverFailedReport(
              inProjectResolver.id,
              VectorMap(project1 -> ResourceNotFound(resource.id, project1))
            ),
            ResolverFailedReport(
              nxv + "cross-project-2",
              VectorMap(
                project3 -> ResourceNotFound(resource.id, project3)
              )
            )
          )
        )
        result shouldEqual None
      }

    }

  }

}

object ResourceResolutionSpec {

  final case class ResourceExample(value: String)

}
