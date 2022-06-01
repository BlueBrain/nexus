package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{IncorrectRev, RevisionNotFound, TagNotFound, TooManyProjections, TooManySources, ViewAlreadyExists, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class CompositeViewsSpec
    extends AbstractDBSpec
    with AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with OptionValues
    with TestHelpers
    with CompositeViewsSetup
    with CompositeViewsFixture {
  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit private val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  "CompositeViews" should {
    val org                      = Label.unsafe("org")
    val orgDeprecated            = Label.unsafe("org-deprecated")
    val apiMappings              = ApiMappings("nxv" -> nxv.base)
    val base                     = nxv.base
    val project                  = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
    val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
    val listProject              = ProjectGen.project("org", "list", base = base, mappings = apiMappings)

    val projectRef = project.ref

    val (orgs, projects) = ProjectSetup
      .init(
        orgsToCreate = org :: orgDeprecated :: Nil,
        projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: listProject :: Nil,
        projectsToDeprecate = deprecatedProject.ref :: Nil,
        organizationsToDeprecate = orgDeprecated :: Nil
      )
      .accepted

    val compositeViews = initViews(orgs, projects).accepted

    val viewSource        = jsonContentOf("composite-view-source.json")
    val viewSourceUpdated = jsonContentOf("composite-view-source-updated.json")

    val viewId      = project.base.iri / uuid.toString
    val otherViewId = iri"http://example.com/other-view"
    def resourceFor(
        id: Iri,
        value: CompositeViewValue,
        rev: Long = 1,
        deprecated: Boolean = false,
        createdAt: Instant = Instant.EPOCH,
        createdBy: Subject = alice.subject,
        updatedAt: Instant = Instant.EPOCH,
        updatedBy: Subject = alice.subject,
        tags: Map[UserTag, Long] = Map.empty,
        source: Json
    ): ViewResource = {
      ResourceF(
        id,
        ResourceUris("views", projectRef, id)(project.apiMappings, project.base),
        rev,
        Set(nxv.View, compositeViewType),
        deprecated,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy,
        schema,
        CompositeView(
          id,
          projectRef,
          value.sources,
          value.projections,
          value.rebuildStrategy,
          uuid,
          tags,
          source,
          Instant.EPOCH
        )
      )
    }

    "create a composite view" when {
      "using JSON source" in {
        compositeViews.create(projectRef, viewSource).accepted shouldEqual resourceFor(
          viewId,
          viewValue,
          source = viewSource.removeAllKeys("token")
        )
      }

      "using CompositeViewFields" in {
        compositeViews.create(otherViewId, projectRef, viewFields).accepted shouldEqual resourceFor(
          otherViewId,
          viewValue,
          source = viewSource.deepMerge(Json.obj("@id" -> otherViewId.asJson)).removeAllKeys("token")
        )
      }

    }

    "reject creating a view" when {
      "view already exists" in {
        compositeViews.create(projectRef, viewSource).rejectedWith[ViewAlreadyExists]
      }
      "there are too many sources" in {
        val fields = viewFields.copy(
          sources = NonEmptySet.of(
            projectFields,
            crossProjectFields,
            remoteProjectFields,
            projectFields.copy(id = Some(iri"http://example/other-source"))
          )
        )
        compositeViews.create(iri"http://example.com/wrong", projectRef, fields).rejectedWith[TooManySources]
      }

      "there are too many projections" in {
        val fields = viewFields.copy(
          projections = NonEmptySet.of(
            esProjectionFields,
            blazegraphProjectionFields,
            esProjectionFields.copy(id = Some(iri"http://example/other-source")),
            esProjectionFields.copy(id = Some(iri"http://example/other-source-2"))
          )
        )
        compositeViews.create(iri"http://example.com/wrong", projectRef, fields).rejectedWith[TooManyProjections]
      }

    }

    "update a view" when {
      "using JSON source" in {
        compositeViews.update(viewId, projectRef, 1L, viewSourceUpdated).accepted shouldEqual resourceFor(
          viewId,
          updatedValue,
          source = viewSourceUpdated.removeAllKeys("token"),
          rev = 2L
        )
      }

      "using CompositeViewFields" in {
        compositeViews.update(otherViewId, projectRef, 1L, updatedFields).accepted shouldEqual resourceFor(
          otherViewId,
          updatedValue,
          source = viewSourceUpdated.deepMerge(Json.obj("@id" -> otherViewId.asJson)).removeAllKeys("token"),
          rev = 2L
        )
      }

    }

    "reject updating a view" when {
      "rev provided is wrong " in {
        compositeViews.update(viewId, projectRef, 1L, viewSourceUpdated).rejectedWith[IncorrectRev]
      }
      "view doesnt exist" in {
        compositeViews
          .update(iri"http://example.com/wrong", projectRef, 1L, viewSourceUpdated)
          .rejectedWith[ViewNotFound]
      }
      "there are too many sources" in {
        val fields = viewFields.copy(
          sources = NonEmptySet.of(
            projectFields,
            crossProjectFields,
            remoteProjectFields,
            projectFields.copy(id = Some(iri"http://example/other-source"))
          )
        )
        compositeViews.update(otherViewId, projectRef, 2L, fields).rejectedWith[TooManySources]
      }

      "there are too many projections" in {
        val fields = viewFields.copy(
          projections = NonEmptySet.of(
            esProjectionFields,
            blazegraphProjectionFields,
            esProjectionFields.copy(id = Some(iri"http://example/other-source")),
            esProjectionFields.copy(id = Some(iri"http://example/other-source-2"))
          )
        )
        compositeViews.update(otherViewId, projectRef, 2L, fields).rejectedWith[TooManyProjections]
      }
    }

    "deprecate a view" in {
      compositeViews.deprecate(otherViewId, projectRef, 2L).accepted shouldEqual resourceFor(
        otherViewId,
        updatedValue,
        source = viewSourceUpdated.deepMerge(Json.obj("@id" -> otherViewId.asJson)).removeAllKeys("token"),
        rev = 3L,
        deprecated = true
      )

    }

    "reject deprecating a view" when {
      "views is already deprecated" in {
        compositeViews.deprecate(otherViewId, projectRef, 3L).rejectedWith[ViewIsDeprecated]
      }
      "incorrect revision is provided" in {
        compositeViews.deprecate(otherViewId, projectRef, 2L).rejectedWith[IncorrectRev]
      }
    }

    "tag a view" when {
      val tag = UserTag.unsafe("mytag")
      "view is not deprecated" in {
        compositeViews.tag(viewId, projectRef, tag, 1L, 2L).accepted
      }

      "view is deprecated" in {
        compositeViews.tag(otherViewId, projectRef, tag, 1L, 3L).accepted
      }
    }

    "reject tagging a view" when {
      "incorrect revision is provided" in {
        val tag = UserTag.unsafe("mytag2")
        compositeViews.tag(viewId, projectRef, tag, 1L, 2L).rejectedWith[IncorrectRev]
      }
      "view is deprecated" in {
        val tag = UserTag.unsafe("mytag3")
        compositeViews.tag(otherViewId, projectRef, tag, 1L, 2L).rejectedWith[IncorrectRev]
      }
      "target view is not found" in {
        val tag = UserTag.unsafe("mytag3")
        compositeViews.tag(iri"http://example.com/wrong", projectRef, tag, 1L, 2L).rejectedWith[ViewNotFound]
      }
    }

    "fetch a view" when {
      "no tag or rev is provided" in {
        compositeViews.fetch(viewId, projectRef).accepted shouldEqual resourceFor(
          viewId,
          updatedValue,
          source = viewSourceUpdated.removeAllKeys("token"),
          rev = 3L,
          tags = Map(UserTag.unsafe("mytag") -> 1)
        )
      }
      "rev is provided" in {
        compositeViews.fetch(IdSegmentRef(viewId, 1), projectRef).accepted shouldEqual resourceFor(
          viewId,
          viewValue,
          source = viewSource.removeAllKeys("token")
        )
      }
      "tag is provided" in {
        val tag = UserTag.unsafe("mytag")
        compositeViews.fetch(IdSegmentRef(viewId, tag), projectRef).accepted shouldEqual
          resourceFor(viewId, viewValue, source = viewSource.removeAllKeys("token"))
      }
    }

    "reject fetching a view" when {
      "view doesn't exist" in {
        compositeViews.fetch(iri"http://example.com/wrong", projectRef).rejectedWith[ViewNotFound]
      }
      "revision doesn't exist" in {
        compositeViews.fetch(IdSegmentRef(viewId, 42), projectRef).rejectedWith[RevisionNotFound]
      }

      "tag doesn't exist" in {
        val tag = UserTag.unsafe("wrongtag")
        compositeViews.fetch(IdSegmentRef(viewId, tag), projectRef).rejectedWith[TagNotFound]
      }
    }

    "finding references" should {

      "get a reference on otherproject from project" in {
        CompositeViews.projectReferenceFinder(compositeViews)(otherProject).accepted shouldEqual
          ProjectReferenceMap.single(projectRef, viewId)
      }
    }
  }

}
