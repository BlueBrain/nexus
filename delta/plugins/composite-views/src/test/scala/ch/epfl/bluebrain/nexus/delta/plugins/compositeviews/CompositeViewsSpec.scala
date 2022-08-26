package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{IncorrectRev, ProjectContextRejection, RevisionNotFound, TagNotFound, ViewAlreadyExists, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOFixedClock}
import io.circe.Json
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class CompositeViewsSpec
    extends DoobieScalaTestFixture
    with Matchers
    with Inspectors
    with IOFixedClock
    with OptionValues
    with CompositeViewsFixture
    with Fixtures {
  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit private val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  "CompositeViews" should {
    val apiMappings       = ApiMappings("nxv" -> nxv.base)
    val base              = nxv.base
    val project           = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val deprecatedProject = ProjectGen.project("org", "proj-deprecated")
    val listProject       = ProjectGen.project("org", "list", base = base, mappings = apiMappings)

    val projectRef = project.ref

    val fetchContext = FetchContextDummy[CompositeViewRejection](
      Map(project.ref -> project.context, listProject.ref -> listProject.context),
      Set(deprecatedProject.ref),
      ProjectContextRejection
    )

    lazy val compositeViews = CompositeViews(
      fetchContext,
      ResolverContextResolution(rcr),
      alwaysValidate,
      crypto,
      config,
      xas
    ).accepted

    val viewSource        = jsonContentOf("composite-view-source.json")
    val viewSourceUpdated = jsonContentOf("composite-view-source-updated.json")

    val viewId      = project.base.iri / uuid.toString
    val otherViewId = iri"http://example.com/other-view"
    def resourceFor(
        id: Iri,
        value: CompositeViewValue,
        rev: Int = 1,
        deprecated: Boolean = false,
        createdAt: Instant = Instant.EPOCH,
        createdBy: Subject = alice.subject,
        updatedAt: Instant = Instant.EPOCH,
        updatedBy: Subject = alice.subject,
        tags: Tags = Tags.empty,
        source: Json
    ): ViewResource = {
      ResourceF(
        id,
        ResourceUris("views", projectRef, id)(project.apiMappings, project.base),
        rev.toLong,
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
    }

    "update a view" when {
      "using JSON source" in {
        compositeViews.update(viewId, projectRef, 1, viewSourceUpdated).accepted shouldEqual resourceFor(
          viewId,
          updatedValue,
          source = viewSourceUpdated.removeAllKeys("token"),
          rev = 2
        )
      }

      "using CompositeViewFields" in {
        compositeViews.update(otherViewId, projectRef, 1, updatedFields).accepted shouldEqual resourceFor(
          otherViewId,
          updatedValue,
          source = viewSourceUpdated.deepMerge(Json.obj("@id" -> otherViewId.asJson)).removeAllKeys("token"),
          rev = 2
        )
      }

    }

    "reject updating a view" when {
      "rev provided is wrong " in {
        compositeViews.update(viewId, projectRef, 1, viewSourceUpdated).rejectedWith[IncorrectRev]
      }
    }

    "deprecate a view" in {
      compositeViews.deprecate(otherViewId, projectRef, 2).accepted shouldEqual resourceFor(
        otherViewId,
        updatedValue,
        source = viewSourceUpdated.deepMerge(Json.obj("@id" -> otherViewId.asJson)).removeAllKeys("token"),
        rev = 3,
        deprecated = true
      )

    }

    "reject deprecating a view" when {
      "views is already deprecated" in {
        compositeViews.deprecate(otherViewId, projectRef, 3).rejectedWith[ViewIsDeprecated]
      }
      "incorrect revision is provided" in {
        compositeViews.deprecate(otherViewId, projectRef, 2).rejectedWith[IncorrectRev]
      }
    }

    "tag a view" when {
      val tag = UserTag.unsafe("mytag")
      "view is not deprecated" in {
        compositeViews.tag(viewId, projectRef, tag, 1, 2).accepted
      }

      "view is deprecated" in {
        compositeViews.tag(otherViewId, projectRef, tag, 1, 3).accepted
      }
    }

    "reject tagging a view" when {
      "incorrect revision is provided" in {
        val tag = UserTag.unsafe("mytag2")
        compositeViews.tag(viewId, projectRef, tag, 1, 2).rejectedWith[IncorrectRev]
      }
      "view is deprecated" in {
        val tag = UserTag.unsafe("mytag3")
        compositeViews.tag(otherViewId, projectRef, tag, 1, 2).rejectedWith[IncorrectRev]
      }
      "target view is not found" in {
        val tag = UserTag.unsafe("mytag3")
        compositeViews.tag(iri"http://example.com/wrong", projectRef, tag, 1, 2).rejectedWith[ViewNotFound]
      }
    }

    "fetch a view" when {
      "no tag or rev is provided" in {
        compositeViews.fetch(viewId, projectRef).accepted shouldEqual resourceFor(
          viewId,
          updatedValue,
          source = viewSourceUpdated.removeAllKeys("token"),
          rev = 3,
          tags = Tags(UserTag.unsafe("mytag") -> 1)
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
  }
}
