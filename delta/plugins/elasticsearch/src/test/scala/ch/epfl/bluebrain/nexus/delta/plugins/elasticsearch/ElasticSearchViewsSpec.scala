package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptySet
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{query => queryPermissions}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDependencyStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeChain
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{FilterBySchema, FilterByType, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import io.circe.literal._
import org.scalatest.Assertion
import org.scalatest.matchers.{BeMatcher, MatchResult}

import java.time.Instant
import java.util.UUID

class ElasticSearchViewsSpec extends CatsEffectSpec with DoobieScalaTestFixture with ConfigFixtures with Fixtures {

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  "An ElasticSearchViews" should {

    val org         = Label.unsafe("org")
    val apiMappings = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
    val base        = nxv.base
    val project     = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val listProject = ProjectGen.project("org", "list", base = base, mappings = apiMappings)

    val projectRef           = ProjectRef.unsafe("org", "proj")
    val deprecatedProjectRef = ProjectRef.unsafe("org", "proj-deprecated")
    val unknownProjectRef    = ProjectRef(org, Label.unsafe("xxx"))

    val mapping  = json"""{ "dynamic": false }""".asObject.value
    val settings = json"""{ "analysis": { } }""".asObject.value

    def currentStateFor(
        id: Iri,
        project: Project,
        uuid: UUID,
        rev: Int,
        indexingRev: IndexingRev,
        deprecated: Boolean,
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject,
        value: ElasticSearchViewValue,
        source: Json,
        tags: Tags
    ): ElasticSearchViewState =
      ElasticSearchViewState(
        id = id,
        project = project.ref,
        uuid = uuid,
        value = value,
        source = source,
        tags = tags,
        rev = rev,
        indexingRev = indexingRev,
        deprecated = deprecated,
        createdAt = createdAt,
        createdBy = createdBy,
        updatedAt = updatedAt,
        updatedBy = updatedBy
      )

    def resourceFor(
        id: Iri,
        project: Project = project,
        uuid: UUID = uuid,
        rev: Int = 1,
        indexingRev: IndexingRev = IndexingRev.init,
        deprecated: Boolean = false,
        createdAt: Instant = Instant.EPOCH,
        createdBy: Subject = alice.subject,
        updatedAt: Instant = Instant.EPOCH,
        updatedBy: Subject = alice.subject,
        value: ElasticSearchViewValue,
        source: Json,
        tags: Tags = Tags.empty
    ): ViewResource =
      currentStateFor(
        id,
        project,
        uuid,
        rev,
        indexingRev,
        deprecated,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy,
        value,
        source,
        tags
      ).toResource(defaultMapping, defaultSettings)

    val viewId          = iri"http://localhost/indexing"
    val aggregateViewId = iri"http://localhost/${genString()}"
    val viewPipelineId  = iri"http://localhost/indexing-pipeline"
    val viewId2         = iri"http://localhost/${genString()}"

    val fetchContext = FetchContextDummy(
      Map(project.ref -> project.context, listProject.ref -> listProject.context),
      Set(deprecatedProjectRef)
    )

    lazy val views: ElasticSearchViews = ElasticSearchViews(
      fetchContext,
      ResolverContextResolution(rcr),
      ValidateElasticSearchView(
        PipeChain.validate(_, registry),
        IO.pure(Set(queryPermissions)),
        (_, _, _) => IO.unit,
        "prefix",
        2,
        xas,
        defaultMapping,
        defaultSettings
      ),
      eventLogConfig,
      "prefix",
      xas,
      defaultMapping,
      defaultSettings,
      clock
    ).accepted

    "create a view" when {
      "using the minimum fields for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(projectRef, source).accepted
      }
      "using a fixed id specified in the IndexingElasticSearchViewValue json" in {
        val source   =
          json"""{"@id": $viewId, "@type": "ElasticSearchView", "mapping": $mapping, "settings": $settings}"""
        val expected = resourceFor(
          id = viewId,
          value = IndexingElasticSearchViewValue(
            resourceTag = None,
            IndexingElasticSearchViewValue.defaultPipeline,
            mapping = Some(mapping),
            settings = Some(settings),
            context = None,
            permission = queryPermissions
          ),
          source = source
        )
        views.create(projectRef, source).accepted shouldEqual expected
      }
      "using the minimum fields a valid pipeline" in {
        val source =
          json"""{"@id": $viewPipelineId, "@type": "ElasticSearchView", "pipeline": [{"name": "filterDeprecated"}], "mapping": $mapping }"""
        views.create(projectRef, source).accepted
      }
      "using an IndexingElasticSearchViewValue" in {
        val value = IndexingElasticSearchViewValue(
          resourceTag = Some(UserTag.unsafe("tag")),
          List(
            PipeStep(FilterBySchema(IriFilter.restrictedTo(iri"http://localhost/schema"))),
            PipeStep(FilterByType(IriFilter.restrictedTo(iri"http://localhost/type"))),
            PipeStep.noConfig(FilterDeprecated.ref)
          ),
          mapping = Some(mapping),
          settings = None,
          context = None,
          permission = queryPermissions
        )
        views.create(viewId2, projectRef, value).accepted
      }
      "using a fixed id specified in the AggregateElasticSearchViewValue json" in {
        val id     = iri"http://localhost/${genString()}"
        val source =
          json"""
            {"@type": "AggregateElasticSearchView",
             "@id": $id,
             "views": [
               {
                 "project": ${project.ref.toString},
                 "viewId": $viewId
               }
             ]}"""
        views.create(projectRef, source).accepted
      }
      "using an AggregateElasticSearchViewValue" in {
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(projectRef, viewId)))
        views.create(aggregateViewId, projectRef, value).accepted

        // Dependency to the referenced project should have been saved
        EntityDependencyStore.directDependencies(projectRef, aggregateViewId, xas).accepted shouldEqual Set(
          DependsOn(projectRef, viewId)
        )
      }
    }
    "reject creating a view" when {
      "a view already exists" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(projectRef, source).rejectedWith[ResourceAlreadyExists]
        views.create(viewId, projectRef, source).rejectedWith[ResourceAlreadyExists]
      }
      "the pipe does not exist" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "pipeline": [ { "name": "xxx" } ], "mapping": $mapping }"""
        views.create(id, projectRef, source).rejectedWith[InvalidPipeline]
      }

      "the pipe configuration is invalid" in {
        val id     = iri"http://localhost/${genString()}"
        val source =
          json"""{"@type": "ElasticSearchView", "pipeline": [ { "name": "filterByType" } ], "mapping": $mapping }"""
        views.create(id, projectRef, source).rejectedWith[InvalidPipeline]
      }

      "the permission is not defined" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping, "permission": "not/exists"}"""
        views.create(id, projectRef, source).rejectedWith[PermissionIsNotDefined]
      }
      "the referenced view does not exist" in {
        val id           = iri"http://localhost/${genString()}"
        val referencedId = iri"http://localhost/${genString()}"
        val value        = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(projectRef, referencedId)))
        views.create(id, projectRef, value).rejectedWith[InvalidViewReferences]
      }

      "too many views are referenced" in {
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(
          NonEmptySet.of(ViewRef(projectRef, aggregateViewId), ViewRef(projectRef, viewPipelineId))
        )
        views.create(id, projectRef, value).rejectedWith[TooManyViewReferences]
      }

      "the referenced project does not exist" in {
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(unknownProjectRef, viewId)))
        views.create(id, projectRef, value).rejectedWith[InvalidViewReferences]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, deprecatedProjectRef, source).rejectedWith[ProjectIsDeprecated]
      }
    }

    "update a view" when {
      "using the minimum fields for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(viewId, projectRef, 1, source).accepted
      }
      "using a fixed id specified in the AggregateElasticSearchViewValue json" in {
        val id     = iri"http://localhost/${genString()}"
        val source =
          json"""
            {"@type": "AggregateElasticSearchView",
             "@id": $id,
             "views": [
               {
                 "project": ${project.ref.toString},
                 "viewId": $viewId
               }
             ]}"""
        views.create(projectRef, source).accepted
        views.update(id, projectRef, 1, source).accepted
      }
    }
    "fail to update a view" when {
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(viewId, projectRef, 100, source).rejectedWith[IncorrectRev]
      }
      "providing an incorrect revision for an AggregateElasticSearchViewValue" in {
        val id     = iri"http://localhost/${genString()}"
        val source =
          json"""
            {"@type": "AggregateElasticSearchView",
             "@id": $id,
             "views": [
               {
                 "project": ${project.ref.toString},
                 "viewId": $viewId
               }
             ]}"""
        views.create(projectRef, source).accepted
        views.update(id, projectRef, 100, source).rejectedWith[IncorrectRev]
      }
      "attempting to update an IndexingElasticSearchViewValue with an AggregateElasticSearchViewValue" in {
        val source =
          json"""
            {"@type": "AggregateElasticSearchView",
             "views": [
               {
                 "project": ${project.ref.toString},
                 "viewId": $viewId
               }
             ]}"""
        views.update(viewId, projectRef, 2, source).rejectedWith[DifferentElasticSearchViewType]
      }
      "the view is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.deprecate(id, projectRef, 1).accepted
        views.update(id, projectRef, 2, source).rejectedWith[ViewIsDeprecated]
      }
      "the target view is not found" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, projectRef, 1, source).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, unknownProjectRef, 1, source).rejectedWith[ProjectNotFound]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, deprecatedProjectRef, 1, source).rejectedWith[ProjectIsDeprecated]
      }
    }

    "deprecate a view" when {
      "using the correct revision" in {
        views.deprecate(viewId, projectRef, 2).accepted
      }
    }

    "fail to deprecate a view" when {
      "the view is already deprecated" in {
        views.deprecate(viewId, projectRef, 3).rejectedWith[ViewIsDeprecated]
      }
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.deprecate(viewId, projectRef, 100).rejectedWith[IncorrectRev]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, projectRef, 1).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, unknownProjectRef, 1).rejectedWith[ProjectNotFound]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, deprecatedProjectRef, 1).rejectedWith[ProjectIsDeprecated]
      }
    }

    "undeprecate a view" when {
      "using the correct revision" in {
        givenADeprecatedView { view =>
          views.undeprecate(view, projectRef, 2).accepted should not be deprecated
          views.fetch(view, projectRef).accepted should not be deprecated
        }
      }
    }

    "fail to undeprecate a view" when {
      "the view is not deprecated" in {
        givenAView { view =>
          views.undeprecate(view, projectRef, 1).assertRejectedWith[ViewIsNotDeprecated]
        }
      }
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        givenADeprecatedView { view =>
          views.undeprecate(view, projectRef, 100).assertRejectedWith[IncorrectRev]
        }
      }
      "the target view is not found" in {
        val nonExistentView = iri"http://localhost/${genString()}"
        views.undeprecate(nonExistentView, projectRef, 2).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        givenAView { view =>
          views.undeprecate(view, unknownProjectRef, 2).assertRejectedWith[ProjectNotFound]
        }
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.undeprecate(id, deprecatedProjectRef, 2).rejectedWith[ProjectIsDeprecated]
      }
    }

    "fetch a view by id" when {
      "no rev nor tag is provided" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.fetch(id, projectRef).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceTag = None,
            IndexingElasticSearchViewValue.defaultPipeline,
            mapping = Some(mapping),
            settings = None,
            context = None,
            permission = queryPermissions
          ),
          source = source
        )
      }
      "a rev is provided" in {
        val id            = iri"http://localhost/${genString()}"
        val source        = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        val updatedSource = json"""{"@type": "ElasticSearchView", "resourceTag": "mytag", "mapping": $mapping}"""
        views.update(id, projectRef, 1, updatedSource).accepted
        views.fetch(IdSegmentRef(id, 1), projectRef).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceTag = None,
            IndexingElasticSearchViewValue.defaultPipeline,
            mapping = Some(mapping),
            settings = None,
            context = None,
            permission = queryPermissions
          ),
          source = source
        )
      }
    }

    "fail to fetch a view" when {
      "the view does not exist" in {
        val id = iri"http://localhost/${genString()}"
        views.fetch(id, projectRef).rejectedWith[ViewNotFound]
      }
      "the revision does not exist" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.fetch(IdSegmentRef(id, 2), projectRef).rejectedWith[RevisionNotFound]
      }
      "attempting any lookup by tag" in {
        val tag    = UserTag.unsafe("mytag")
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.fetch(IdSegmentRef(id, tag), projectRef).rejectedWith[FetchByTagNotSupported]
      }
    }

    "writing to the default view should fail" when {
      val defaultViewId = iri"nxv:defaultElasticSearchIndex"
      "deprecating" in {
        views.deprecate(defaultViewId, projectRef, 1).rejectedWith[ViewIsDefaultView]
      }

      "updating" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(defaultViewId, projectRef, 1, source).rejectedWith[ViewIsDefaultView]
      }
    }

    def givenAView(test: String => Assertion): Assertion = {
      val id     = genString()
      val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
      views.create(id, projectRef, source).accepted
      test(id)
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
