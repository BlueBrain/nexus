package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceAccess, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolutionSuite.ResourceExample
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver.CrossProjectResolver
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverResolutionRejection.*
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.CrossProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{IdentityResolution, Priority, Resolver, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

import java.time.Instant

class ResolverResolutionSuite extends NexusSuite {

  private val realm = Label.unsafe("wonderland")
  private val alice = User("alice", realm)
  private val bob   = User("bob", realm)

  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  private val project1 = ProjectRef.unsafe("org", "project1")
  private val project2 = ProjectRef.unsafe("org", "project2")
  private val project3 = ProjectRef.unsafe("org", "project3")

  private val checkAcls: (ProjectRef, Set[Identity]) => IO[Boolean] =
    (p: ProjectRef, identities: Set[Identity]) =>
      p match {
        case `project1` if identities == Set(alice) || identities == Set(bob) => IO.pure(true)
        case `project2` if identities == Set(bob)                             => IO.pure(true)
        case `project3` if identities == Set(alice)                           => IO.pure(true)
        case _                                                                => IO.pure(false)
      }

  private val resource = ResourceF(
    id = nxv + "example1",
    access = ResourceAccess(uri"/example1"),
    rev = 5,
    types = Set(nxv + "ResourceExample", nxv + "ResourceExample2"),
    deprecated = true,
    createdAt = Instant.now(),
    createdBy = alice,
    updatedAt = Instant.now(),
    updatedBy = alice,
    schema = Latest(schemas + "ResourceExample"),
    value = ResourceExample("myResource")
  )

  private val inProjectResolver = ResolverGen.inProject(nxv + "in-project-proj-1", project1)

  private def crossProjectResolver(
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
      Json.obj()
    )

  def listResolvers(resolvers: List[Resolver]): ProjectRef => IO[List[Resolver]] = (_: ProjectRef) => IO.pure(resolvers)

  private val emptyResolverListQuery = listResolvers(List.empty[Resolver])

  val noResolverFetch: (Iri, ProjectRef) => IO[Nothing] =
    (_: Iri, projectRef: ProjectRef) => IO.raiseError(ResolverNotFound(nxv + "not-found", projectRef))

  def fetchResolver(resolver: Resolver): (Iri, ProjectRef) => IO[Resolver] =
    (id: Iri, projectRef: ProjectRef) =>
      if (id == resolver.id) IO.pure(resolver)
      else IO.raiseError(ResolverNotFound(id, projectRef))

  def fetchResource(
      projectRef: ProjectRef
  ): (ResourceRef, ProjectRef) => FetchF[ResourceExample] =
    (_: ResourceRef, p: ProjectRef) =>
      p match {
        case `projectRef` => IO.pure(Some(resource))
        case _            => IO.none
      }

  private def singleResolverResolution(resourceProject: ProjectRef, resolver: Resolver, excludeDeprecated: Boolean) =
    ResourceResolution(
      checkAcls,
      emptyResolverListQuery,
      fetchResolver(resolver),
      fetchResource(resourceProject),
      excludeDeprecated
    )

  private def multipleResolverResolution(
      resourceProject: ProjectRef,
      excludeDeprecated: Boolean,
      resolvers: Resolver*
  ) =
    ResourceResolution(
      checkAcls,
      listResolvers(resolvers.toList),
      noResolverFetch,
      fetchResource(resourceProject),
      excludeDeprecated
    )

  private val inProjectResolution = singleResolverResolution(project1, inProjectResolver, excludeDeprecated = false)

  private val resource1NotFound = None
  private val resource1Found    = Some(resource)

  test("Using an in-project resolver fails if the resolver can't be found") {

    val unknown       = nxv + "xxx"
    val expectedError = ResolverReport.failed(
      unknown,
      project1 -> WrappedResolverRejection(ResolverNotFound(unknown, project1))
    )
    inProjectResolution
      .resolve(Latest(resource.id), project1, unknown)
      .assertEquals(Left(expectedError))
  }

  test("Using an in-project resolver fails if the resource can't be found in the project") {
    val expectedReport = ResolverReport.failed(
      inProjectResolver.id,
      project2 -> ResourceNotFound(resource.id, project2)
    )
    inProjectResolution
      .resolveReport(
        Latest(resource.id),
        project2,
        inProjectResolver.id
      )
      .assertEquals((expectedReport, resource1NotFound))
  }

  test("Using an in-project resolver succeeds if the resource can be fetched") {
    val expectedReport = ResolverReport.success(inProjectResolver.id, project1)
    val expectedResult = Some(resource)
    inProjectResolution
      .resolveReport(Latest(resource.id), project1, inProjectResolver.id)
      .assertEquals((expectedReport, expectedResult))
  }

  test("Using an in-project resolver excluding deprecated resources fails with a deprecated resource") {
    val noDeprecated = singleResolverResolution(project1, inProjectResolver, excludeDeprecated = true)

    val expectedReport = ResolverReport.failed(
      inProjectResolver.id,
      project1 -> ResourceIsDeprecated(resource.id, project1)
    )

    noDeprecated
      .resolveReport(Latest(resource.id), project1, inProjectResolver.id)
      .assertEquals((expectedReport, resource1NotFound))
  }

  test("Using a cross-project resolver with current caller succeeds") {
    val resolver           = crossProjectResolver("use-current", 40, identityResolution = UseCurrentCaller)
    val resolverResolution = singleResolverResolution(project3, resolver, excludeDeprecated = false)

    val successAtProject3 = ResolverReport.success(
      resolver.id,
      project3,
      project1 -> ResourceNotFound(resource.id, project1),
      project2 -> ProjectAccessDenied(project2, UseCurrentCaller)
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((successAtProject3, resource1Found))
  }

  test("Using a cross-project resolver with current caller and limiting on types succeeds") {
    val acceptedTypes      = resource.types + nxv.Schema
    val resolver           =
      crossProjectResolver("use-current", 40, resourceTypes = acceptedTypes, identityResolution = UseCurrentCaller)
    val resolverResolution = singleResolverResolution(project3, resolver, excludeDeprecated = false)

    val successAtProject3 = ResolverReport.success(
      resolver.id,
      project3,
      project1 -> ResourceNotFound(resource.id, project1),
      project2 -> ProjectAccessDenied(project2, UseCurrentCaller)
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((successAtProject3, resource1Found))
  }

  test("Using a cross-project resolver with current caller fails if the caller has no access to the project") {
    val resolver           = crossProjectResolver("use-current", 40, identityResolution = UseCurrentCaller)
    val resolverResolution = singleResolverResolution(project2, resolver, excludeDeprecated = false)

    val failedReport = ResolverReport.failed(
      resolver.id,
      project1 -> ResourceNotFound(resource.id, project1),
      project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
      project3 -> ResourceNotFound(resource.id, project3)
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((failedReport, resource1NotFound))
  }

  test("Using a cross-project resolver with current caller fails if the resource type is not defined") {
    val acceptedTypes      = Set(nxv.Schema)
    val resolver           =
      crossProjectResolver("use-current", 40, resourceTypes = acceptedTypes, identityResolution = UseCurrentCaller)
    val resolverResolution = singleResolverResolution(project3, resolver, excludeDeprecated = false)

    val failedReport = ResolverReport.failed(
      resolver.id,
      project1 -> ResourceNotFound(resource.id, project1),
      project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
      project3 -> ResourceTypesDenied(project3, resource.types)
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((failedReport, resource1NotFound))
  }

  test("Using a cross-project resolver with provided identities succeeds") {
    val resolver           = crossProjectResolver("provided-identities", 40, identityResolution = ProvidedIdentities(Set(bob)))
    val resolverResolution = singleResolverResolution(project2, resolver, excludeDeprecated = false)

    val successAtProject2 = ResolverReport.success(
      resolver.id,
      project2,
      project1 -> ResourceNotFound(resource.id, project1)
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((successAtProject2, resource1Found))
  }

  test("Using a cross-project resolver with provided identities and limiting on types succeeds") {
    val acceptedTypes      = resource.types + nxv.Schema
    val resolver           = crossProjectResolver(
      "provided-identities",
      40,
      resourceTypes = acceptedTypes,
      identityResolution = ProvidedIdentities(Set(bob))
    )
    val resolverResolution = singleResolverResolution(project2, resolver, excludeDeprecated = false)

    val successAtProject2 = ResolverReport.success(
      resolver.id,
      project2,
      project1 -> ResourceNotFound(resource.id, project1)
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((successAtProject2, resource1Found))
  }

  test("Using a cross-project resolver with provided identities fail if the identity has no access") {
    val resolver           = crossProjectResolver("provided-identities", 40, identityResolution = ProvidedIdentities(Set(bob)))
    val resolverResolution = singleResolverResolution(project3, resolver, excludeDeprecated = false)

    val failedReport = ResolverReport.failed(
      resolver.id,
      project1 -> ResourceNotFound(resource.id, project1),
      project2 -> ResourceNotFound(resource.id, project2),
      project3 -> ProjectAccessDenied(project3, ProvidedIdentities(Set(bob)))
    )

    resolverResolution
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((failedReport, resource1NotFound))
  }

  test("Using an cross-project resolver excluding deprecated resources fails with a deprecated resource") {
    val resolver     = crossProjectResolver("use-current", 40, identityResolution = UseCurrentCaller)
    val noDeprecated = singleResolverResolution(project3, resolver, excludeDeprecated = true)

    val expectedReport = ResolverReport.failed(
      resolver.id,
      project1 -> ResourceNotFound(resource.id, project1),
      project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
      project3 -> ResourceIsDeprecated(resource.id, project3)
    )

    noDeprecated
      .resolveReport(Latest(resource.id), project1, resolver.id)
      .assertEquals((expectedReport, resource1NotFound))
  }

  test("Using multiple resolvers succeeds after a first failure") {
    val resolution = multipleResolverResolution(
      project1,
      false,
      crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
      crossProjectResolver("cross-project-2", priority = 40),
      inProjectResolver
    )

    val expectedReport = ResourceResolutionReport(
      ResolverReport.failed(
        nxv + "cross-project-1",
        project1 -> ResourceTypesDenied(project1, resource.types),
        project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
        project3 -> ResourceNotFound(resource.id, project3)
      ),
      ResolverReport.success(inProjectResolver.id, project1)
    )

    resolution.resolveReport(Latest(resource.id), project1).assertEquals((expectedReport, resource1Found))
  }

  test("Using multiple resolvers succeeds with the last resolver") {
    val resolution = multipleResolverResolution(
      project3,
      false,
      crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
      crossProjectResolver("cross-project-2", priority = 40, projects = NonEmptyList.of(project3)),
      inProjectResolver
    )

    val expectedReport = ResourceResolutionReport(
      ResolverReport.failed(
        nxv + "cross-project-1",
        project1 -> ResourceNotFound(resource.id, project1),
        project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
        project3 -> ResourceTypesDenied(project3, resource.types)
      ),
      ResolverReport.failed(
        inProjectResolver.id,
        project1 -> ResourceNotFound(resource.id, project1)
      ),
      ResolverReport.success(nxv + "cross-project-2", project3)
    )

    resolution.resolveReport(Latest(resource.id), project1).assertEquals((expectedReport, resource1Found))
  }

  test("Using multiple resolvers fails if no resolver matches") {
    val resolution = multipleResolverResolution(
      project2,
      false,
      crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
      crossProjectResolver("cross-project-2", priority = 40, projects = NonEmptyList.of(project3)),
      inProjectResolver
    )

    val expectedReport = ResourceResolutionReport(
      ResolverReport.failed(
        nxv + "cross-project-1",
        project1 -> ResourceNotFound(resource.id, project1),
        project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
        project3 -> ResourceNotFound(resource.id, project3)
      ),
      ResolverReport.failed(
        inProjectResolver.id,
        project1 -> ResourceNotFound(resource.id, project1)
      ),
      ResolverReport.failed(
        nxv + "cross-project-2",
        project3 -> ResourceNotFound(resource.id, project3)
      )
    )

    resolution.resolveReport(Latest(resource.id), project1).assertEquals((expectedReport, resource1NotFound))
  }

}

object ResolverResolutionSuite {
  final case class ResourceExample(value: String)

}
