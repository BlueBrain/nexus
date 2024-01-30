package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.data.NonEmptySet
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsGen.resourceFor
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import org.scalatest.Assertion
import org.scalatest.matchers.{BeMatcher, MatchResult}

import java.util.UUID

class BlazegraphViewsSpec extends CatsEffectSpec with DoobieScalaTestFixture with ConfigFixtures with Fixtures {

  "BlazegraphViews" when {
    val uuid                  = UUID.randomUUID()
    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

    val prefix = "prefix"

    val realm                   = Label.unsafe("myrealm")
    val bob                     = User("Bob", realm)
    implicit val caller: Caller = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))

    val indexingValue  = IndexingBlazegraphViewValue(
      None,
      None,
      IriFilter.None,
      IriFilter.None,
      None,
      includeMetadata = false,
      includeDeprecated = false,
      permissions.query
    )
    val indexingSource = jsonContentOf("indexing-view-source.json")

    val updatedIndexingValue  = indexingValue.copy(resourceTag = Some(UserTag.unsafe("v1.5")))
    val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

    val indexingViewId  = nxv + "indexing-view"
    val indexingViewId2 = nxv + "indexing-view3"

    val undefinedPermission = Permission.unsafe("not/defined")

    val base              = nxv.base
    val project           = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings.empty)
    val deprecatedProject = ProjectGen.project("org", "proj-deprecated")
    val projectRef        = project.ref

    val viewRef         = ViewRef(project.ref, indexingViewId)
    val aggregateValue  = AggregateBlazegraphViewValue(None, None, NonEmptySet.of(viewRef))
    val aggregateViewId = nxv + "aggregate-view"
    val aggregateSource = jsonContentOf("aggregate-view-source.json")

    val tag = UserTag.unsafe("v1.5")

    val doesntExistId = nxv + "doesntexist"

    val fetchContext = FetchContextDummy(Map(project.ref -> project.context), Set(deprecatedProject.ref))

    lazy val views: BlazegraphViews = BlazegraphViews(
      fetchContext,
      ResolverContextResolution(rcr),
      ValidateBlazegraphView(
        IO.pure(Set(permissions.query)),
        2,
        xas
      ),
      _ => IO.unit,
      eventLogConfig,
      prefix,
      xas,
      clock
    ).accepted

    "creating a view" should {
      "reject when referenced view does not exist" in {
        views
          .create(aggregateViewId, projectRef, aggregateValue)
          .rejected shouldEqual InvalidViewReferences(Set(viewRef))
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
          .rejectedWith[ProjectNotFound]
      }

      "reject when the project is deprecated" in {
        views
          .create(indexingViewId, deprecatedProject.ref, indexingValue)
          .rejectedWith[ProjectIsDeprecated]
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
        views.update(indexingViewId, projectRef, 1, updatedIndexingValue).accepted shouldEqual resourceFor(
          indexingViewId,
          projectRef,
          updatedIndexingValue,
          uuid,
          updatedIndexingSource,
          2,
          indexingRev = 2,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "update an AggregateBlazegraphView" in {
        views.update(aggregateViewId, projectRef, 1, aggregateValue).accepted shouldEqual resourceFor(
          aggregateViewId,
          projectRef,
          aggregateValue,
          uuid,
          aggregateSource,
          2,
          createdBy = bob,
          updatedBy = bob
        )
      }

      "reject when view doesn't exits" in {
        views.update(indexingViewId2, projectRef, 1, indexingValue).rejected shouldEqual ViewNotFound(
          indexingViewId2,
          projectRef
        )
      }

      "reject when incorrect revision is provided" in {
        views.update(indexingViewId, projectRef, 1, indexingValue).rejected shouldEqual IncorrectRev(
          1,
          2
        )
      }

      "reject when trying to change the view type" in {
        views
          .update(indexingViewId, projectRef, 2, aggregateValue)
          .rejected shouldEqual DifferentBlazegraphViewType(
          indexingViewId,
          BlazegraphViewType.AggregateBlazegraphView,
          BlazegraphViewType.IndexingBlazegraphView
        )
      }

      "reject when referenced view does not exist" in {
        val nonExistentViewRef            = ViewRef(projectRef, indexingViewId2)
        val aggregateValueWithInvalidView =
          AggregateBlazegraphViewValue(None, None, NonEmptySet.of(nonExistentViewRef))
        views
          .update(aggregateViewId, projectRef, 2, aggregateValueWithInvalidView)
          .rejected shouldEqual InvalidViewReferences(Set(nonExistentViewRef))
      }

      "reject when view is deprecated" in {
        views.create(indexingViewId2, projectRef, indexingValue).accepted
        views.deprecate(indexingViewId2, projectRef, 1).accepted
        views.update(indexingViewId2, projectRef, 2, indexingValue).rejected shouldEqual ViewIsDeprecated(
          indexingViewId2
        )
      }

      "reject when referenced view is deprecated" in {
        val deprecatedViewRef             = ViewRef(projectRef, indexingViewId2)
        val aggregateValueWithInvalidView =
          AggregateBlazegraphViewValue(None, None, NonEmptySet.of(deprecatedViewRef))
        views
          .update(aggregateViewId, projectRef, 2, aggregateValueWithInvalidView)
          .rejected shouldEqual InvalidViewReferences(Set(deprecatedViewRef))
      }

      "reject when the permission is not defined" in {
        views
          .update(indexingViewId, projectRef, 2, indexingValue.copy(permission = undefinedPermission))
          .rejected shouldEqual PermissionIsNotDefined(undefinedPermission)
      }

    }

    "deprecating a view" should {
      "deprecate the view" in {
        views.deprecate(aggregateViewId, projectRef, 2).accepted should be(deprecated)
        views.fetch(aggregateViewId, projectRef).accepted should be(deprecated)
      }

      "reject when view doesn't exits" in {
        val doesntExist = nxv + "doesntexist"
        views.deprecate(doesntExist, projectRef, 1).rejected shouldEqual ViewNotFound(doesntExist, projectRef)
      }

      "reject when incorrect revision is provided" in {
        givenAView { view =>
          views.deprecate(view, projectRef, 42).rejected shouldEqual IncorrectRev(42, 1)
          views.fetch(view, projectRef).accepted should not be deprecated
        }
      }
    }

    "undeprecating a view" should {
      "undeprecate the view" in {
        givenADeprecatedView { view =>
          views.undeprecate(view, projectRef, 2).accepted should not be deprecated
          views.fetch(view, projectRef).accepted should not be deprecated
        }
      }

      "reject when view doesn't exits" in {
        val doesntExist = nxv + "doesntexist"
        views.undeprecate(doesntExist, projectRef, 1).rejected shouldEqual ViewNotFound(doesntExist, projectRef)
      }

      "reject when incorrect revision is provided" in {
        givenADeprecatedView { view =>
          views.undeprecate(view, projectRef, 42).rejected shouldEqual IncorrectRev(42, 2)
          views.fetch(view, projectRef).accepted should be(deprecated)
        }
      }

      "reject when view is not deprecated" in {
        givenAView { view =>
          views.undeprecate(view, projectRef, 1).rejected shouldEqual ViewIsNotDeprecated(nxv + view)
        }
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
          2,
          indexingRev = 2,
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

      "reject when fetching a view by tag" in {
        val id = IdSegmentRef.Tag(aggregateViewId, tag)
        views.fetch(id, projectRef).rejected shouldEqual FetchByTagNotSupported(id)
      }

      "reject when the revision does not exit" in {
        views.fetch(IdSegmentRef(indexingViewId, 42), projectRef).rejected shouldEqual RevisionNotFound(42, 2)
      }

      "reject when the view is not found" in {
        views.fetch(doesntExistId, projectRef).rejected shouldEqual ViewNotFound(doesntExistId, projectRef)
      }
    }

    "writing to the default view should fail" when {
      val defaultViewId = nxv + "defaultSparqlIndex"
      "deprecating" in {
        views.deprecate(defaultViewId, projectRef, 1).rejected shouldEqual ViewIsDefaultView
      }

      "updating" in {
        views.update(defaultViewId, projectRef, 1, indexingSource).rejected shouldEqual ViewIsDefaultView
      }
    }

    def givenAView(test: String => Assertion): Assertion = {
      val viewId = genString()
      views.create(viewId, projectRef, indexingValue).accepted
      test(viewId)
    }

    def givenADeprecatedView(test: String => Assertion): Assertion = {
      givenAView { view =>
        views.deprecate(view, projectRef, 1).accepted
        test(view)
      }
    }

    def deprecated: BeMatcher[ViewResource] = BeMatcher { view =>
      MatchResult(
        view.deprecated,
        s"view was not deprecated",
        s"view was deprecated"
      )
    }
  }
}
