package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsGen.resourceFor
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{permissions, _}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class BlazegraphViewsSpec
    extends AbstractDBSpec
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with ConfigFixtures
    with RemoteContextResolutionFixture {

  "BlazegraphViews" when {
    val uuid                      = UUID.randomUUID()
    implicit val uuidF: UUIDF     = UUIDF.fixed(uuid)
    implicit val sc: Scheduler    = Scheduler.global
    val realm                     = Label.unsafe("myrealm")
    val bob                       = User("Bob", realm)
    implicit val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

    val indexingValue  = IndexingBlazegraphViewValue(
      Set.empty,
      Set.empty,
      None,
      includeMetadata = false,
      includeDeprecated = false,
      permissions.query
    )
    val indexingSource = jsonContentOf("indexing-view-source.json")

    val updatedIndexingValue  = indexingValue.copy(resourceTag = Some(TagLabel.unsafe("v1.5")))
    val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

    val indexingViewId  = nxv + "indexing-view"
    val indexingViewId2 = nxv + "indexing-view3"

    val undefinedPermission = Permission.unsafe("not/defined")

    val org                      = Label.unsafe("org")
    val orgDeprecated            = Label.unsafe("org-deprecated")
    val base                     = nxv.base
    val project                  = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings.empty)
    val project2                 = ProjectGen.project("org", "proj2", base = base, mappings = ApiMappings.empty)
    val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
    val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
    val projectRef               = project.ref

    val viewRef          = ViewRef(project.ref, indexingViewId)
    val aggregateValue   = AggregateBlazegraphViewValue(NonEmptySet.of(viewRef))
    val aggregateViewId  = nxv + "aggregate-view"
    val aggregateViewId2 = nxv + "aggregate-view2"
    val aggregateSource  = jsonContentOf("aggregate-view-source.json")

    val tag = TagLabel.unsafe("v1.5")

    val doesntExistId = nxv + "doesntexist"

    val (orgs, projs) =
      ProjectSetup
        .init(
          orgsToCreate = org :: orgDeprecated :: Nil,
          projectsToCreate = project :: project2 :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
          projectsToDeprecate = deprecatedProject.ref :: Nil,
          organizationsToDeprecate = orgDeprecated :: Nil
        )
        .accepted

    val views: BlazegraphViews = BlazegraphViewsSetup.init(orgs, projs, permissions.query)

    "creating a view" should {
      "reject when referenced view does not exist" in {
        views
          .create(aggregateViewId, projectRef, aggregateValue)
          .rejected shouldEqual InvalidViewReference(viewRef)
      }

      "create an IndexingBlazegraphView" in {
        views.create(indexingViewId, projectRef, indexingValue).accepted shouldEqual resourceFor(
          indexingViewId,
          projectRef,
          indexingValue,
          uuid,
          indexingSource,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "create an AggregateBlazegraphViewValue" in {
        views.create(aggregateViewId, projectRef, aggregateValue).accepted shouldEqual resourceFor(
          aggregateViewId,
          projectRef,
          aggregateValue,
          uuid,
          aggregateSource,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "reject when the project does not exist" in {
        val nonExistent = ProjectGen.project("org", "nonexistent").ref
        views
          .create(indexingViewId, nonExistent, indexingValue)
          .rejected shouldEqual WrappedProjectRejection(ProjectRejection.ProjectNotFound(nonExistent))
      }

      "reject when the project is deprecated" in {
        views
          .create(indexingViewId, deprecatedProject.ref, indexingValue)
          .rejected shouldEqual WrappedProjectRejection(ProjectRejection.ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject when the organization is deprecated" in {
        views
          .create(indexingViewId, projectWithDeprecatedOrg.ref, indexingValue)
          .rejected shouldEqual WrappedOrganizationRejection(
          OrganizationRejection.OrganizationIsDeprecated(projectWithDeprecatedOrg.organizationLabel)
        )
      }

      "reject when view already exists" in {
        views.create(aggregateViewId, projectRef, aggregateValue).rejected shouldEqual
          ResourceAlreadyExists(aggregateViewId, projectRef)
      }

      "reject when the permission is not defined" in {
        views
          .create(indexingViewId2, projectRef, indexingValue.copy(permission = undefinedPermission))
          .rejected shouldEqual PermissionIsNotDefined(undefinedPermission)
      }

    }

    "updating a view" should {

      "update an IndexingBlazegraphView" in {
        views.update(indexingViewId, projectRef, 1L, updatedIndexingValue).accepted shouldEqual resourceFor(
          indexingViewId,
          projectRef,
          updatedIndexingValue,
          uuid,
          updatedIndexingSource,
          2L,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "update an AggregateBlazegraphView" in {
        views.update(aggregateViewId, projectRef, 1L, aggregateValue).accepted shouldEqual resourceFor(
          aggregateViewId,
          projectRef,
          aggregateValue,
          uuid,
          aggregateSource,
          2L,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "reject when view doesn't exits" in {
        views.update(indexingViewId2, projectRef, 1L, indexingValue).rejected shouldEqual ViewNotFound(
          indexingViewId2,
          projectRef
        )
      }

      "reject when incorrect revision is provided" in {
        views.update(indexingViewId, projectRef, 1L, indexingValue).rejected shouldEqual IncorrectRev(
          1L,
          2L
        )
      }

      "reject when trying to change the view type" in {
        views
          .update(indexingViewId, projectRef, 2L, aggregateValue)
          .rejected shouldEqual DifferentBlazegraphViewType(
          indexingViewId,
          BlazegraphViewType.AggregateBlazegraphView,
          BlazegraphViewType.IndexingBlazegraphView
        )
      }

      "reject when referenced view does not exist" in {
        val nonExistentViewRef            = ViewRef(projectRef, indexingViewId2)
        val aggregateValueWithInvalidView =
          AggregateBlazegraphViewValue(NonEmptySet.of(nonExistentViewRef))
        views
          .update(aggregateViewId, projectRef, 2L, aggregateValueWithInvalidView)
          .rejected shouldEqual InvalidViewReference(nonExistentViewRef)
      }

      "reject when view is deprecated" in {
        views.create(indexingViewId2, projectRef, indexingValue).accepted
        views.deprecate(indexingViewId2, projectRef, 1L).accepted
        views.update(indexingViewId2, projectRef, 2L, indexingValue).rejected shouldEqual ViewIsDeprecated(
          indexingViewId2
        )
      }

      "reject when referenced view is deprecated" in {
        val nonExistentViewRef            = ViewRef(projectRef, indexingViewId2)
        val aggregateValueWithInvalidView =
          AggregateBlazegraphViewValue(NonEmptySet.of(nonExistentViewRef))
        views
          .update(aggregateViewId, projectRef, 2L, aggregateValueWithInvalidView)
          .rejected shouldEqual InvalidViewReference(nonExistentViewRef)
      }

      "reject when the permission is not defined" in {
        views
          .update(indexingViewId, projectRef, 2L, indexingValue.copy(permission = undefinedPermission))
          .rejected shouldEqual PermissionIsNotDefined(undefinedPermission)
      }

    }

    "tagging a view" should {
      "tag a view" in {
        views.tag(aggregateViewId, projectRef, tag, tagRev = 1, 2L).accepted shouldEqual resourceFor(
          aggregateViewId,
          projectRef,
          aggregateValue,
          uuid,
          aggregateSource,
          3L,
          tags = Map(tag -> 1L),
          createdBy = bob,
          updatedBy = bob
        )
      }

      "reject when view doesn't exits" in {
        views.tag(doesntExistId, projectRef, tag, tagRev = 1, 2L).rejected shouldEqual ViewNotFound(
          doesntExistId,
          projectRef
        )
      }

      "reject when target revision doesn't exist" in {
        views.tag(indexingViewId, projectRef, tag, tagRev = 42L, 2L).rejected shouldEqual RevisionNotFound(
          42L,
          2L
        )
      }

      "reject when incorrect revision is provided" in {
        views.tag(indexingViewId, projectRef, tag, tagRev = 1L, 1L).rejected shouldEqual IncorrectRev(
          1L,
          2L
        )
      }

      "reject when view is deprecated" in {
        views.tag(indexingViewId2, projectRef, tag, tagRev = 1L, 2L).rejected shouldEqual ViewIsDeprecated(
          indexingViewId2
        )
      }

    }

    "deprecating a view" should {
      "deprecate the view" in {
        views.deprecate(aggregateViewId, projectRef, 3L).accepted shouldEqual resourceFor(
          aggregateViewId,
          projectRef,
          aggregateValue,
          uuid,
          aggregateSource,
          4L,
          deprecated = true,
          tags = Map(tag -> 1L),
          createdBy = bob,
          updatedBy = bob
        )

      }

      "reject when view doesn't exits" in {
        val doesntExist = nxv + "doesntexist"
        views.deprecate(doesntExist, projectRef, 1L).rejected shouldEqual ViewNotFound(
          doesntExist,
          projectRef
        )
      }

      "reject when incorrect revision is provided" in {
        views.deprecate(indexingViewId, projectRef, 42L).rejected shouldEqual IncorrectRev(
          42L,
          2L
        )
      }

      "reject when view is deprecated" in {
        views.deprecate(indexingViewId2, projectRef, 2L).rejected shouldEqual ViewIsDeprecated(
          indexingViewId2
        )
      }

    }

    "fetching a view" should {
      "fetch a view" in {
        views.fetch(indexingViewId, projectRef).accepted shouldEqual resourceFor(
          indexingViewId,
          projectRef,
          updatedIndexingValue,
          uuid,
          updatedIndexingSource,
          2L,
          createdBy = bob,
          updatedBy = bob
        )

      }

      "fetch a view by tag" in {
        views.fetch(IdSegmentRef(aggregateViewId, tag), projectRef).accepted shouldEqual resourceFor(
          aggregateViewId,
          projectRef,
          aggregateValue,
          uuid,
          aggregateSource,
          1L,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "fetch a view by rev" in {
        views.fetch(IdSegmentRef(indexingViewId, 1), projectRef).accepted shouldEqual resourceFor(
          indexingViewId,
          projectRef,
          indexingValue,
          uuid,
          indexingSource,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "reject when the tag does not exist" in {
        val notFound = TagLabel.unsafe("notfound")
        views.fetch(IdSegmentRef(aggregateViewId, notFound), projectRef).rejected shouldEqual TagNotFound(notFound)
      }

      "reject when the revision does not exit" in {
        views.fetch(IdSegmentRef(indexingViewId, 42), projectRef).rejected shouldEqual RevisionNotFound(42L, 2L)
      }

      "reject when the view is not found" in {
        views.fetch(doesntExistId, projectRef).rejected shouldEqual ViewNotFound(doesntExistId, projectRef)
      }
    }

    "fetching SSE" should {
      val allEvents = SSEUtils.list(
        indexingViewId  -> BlazegraphViewCreated,
        aggregateViewId -> BlazegraphViewCreated,
        indexingViewId  -> BlazegraphViewUpdated,
        aggregateViewId -> BlazegraphViewUpdated,
        indexingViewId2 -> BlazegraphViewCreated,
        indexingViewId2 -> BlazegraphViewDeprecated,
        aggregateViewId -> BlazegraphViewTagAdded,
        aggregateViewId -> BlazegraphViewDeprecated
      )

      "get events from start" in {
        val streams = List(
          views.events(NoOffset),
          views.events(org, NoOffset).accepted,
          views.events(projectRef, NoOffset).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(allEvents.size.toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents
        }
      }
      "get events from offset 2" in {
        val streams = List(
          views.events(Sequence(2L)),
          views.events(org, Sequence(2L)).accepted,
          views.events(projectRef, Sequence(2L)).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take((allEvents.size - 2).toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents.drop(2)
        }
      }
      "reject when the project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        views.events(projectRef, NoOffset).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }
      "reject when the organization does not exist" in {
        val org = Label.unsafe("other")
        views.events(org, NoOffset).rejected shouldEqual WrappedOrganizationRejection(OrganizationNotFound(org))
      }
    }

    "finding references" should {

      "get a reference on project from project2" in {
        views.create(aggregateViewId2, project2.ref, aggregateValue).accepted

        BlazegraphViews.projectReferenceFinder(views)(projectRef).accepted shouldEqual
          ProjectReferenceMap.single(project2.ref, aggregateViewId2)
      }

    }
  }
}
