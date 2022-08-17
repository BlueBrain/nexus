package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DifferentElasticSearchViewType, IncorrectRev, InvalidPipeline, InvalidViewReferences, PermissionIsNotDefined, ProjectContextRejection, ResourceAlreadyExists, RevisionNotFound, TagNotFound, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.AggregateElasticSearch
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{FilterBySchema, FilterByType, FilterDeprecated, PipeConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, EitherValuable, IOFixedClock}
import io.circe.Json
import io.circe.literal._
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ElasticSearchViewsSpec
    extends DoobieScalaTestFixture
    with Matchers
    with Inspectors
    with OptionValues
    with EitherValuable
    with IOFixedClock
    with ConfigFixtures
    with Fixtures {

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val defaultEsMapping  = defaultElasticsearchMapping.accepted
  private val defaultEsSettings = defaultElasticsearchSettings.accepted

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
        deprecated,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy,
        value,
        source,
        tags
      ).toResource(project.apiMappings, project.base, defaultEsMapping, defaultEsSettings)

    val viewId         = iri"http://localhost/indexing"
    val viewPipelineId = iri"http://localhost/indexing-pipeline"
    val viewId2        = iri"http://localhost/${genString()}"

    val fetchContext = FetchContextDummy[ElasticSearchViewRejection](
      Map(project.ref -> project.context, listProject.ref -> listProject.context),
      Set(deprecatedProjectRef),
      ProjectContextRejection
    )

    lazy val views: ElasticSearchViews = ElasticSearchViews(
      fetchContext,
      ResolverContextResolution(rcr),
      ValidateElasticSearchView(
        PipeConfig.coreConfig.rightValue,
        UIO.pure(Set(queryPermissions)),
        (_, _, _) => IO.unit,
        "prefix",
        xas
      ),
      eventLogConfig,
      xas
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
            FilterBySchema(Set(iri"http://localhost/schema")),
            FilterByType(Set(iri"http://localhost/type")),
            FilterDeprecated()
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
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(projectRef, viewId)))
        views.create(id, projectRef, value).accepted
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
      "the referenced project does not exist" in {
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(unknownProjectRef, viewId)))
        views.create(id, projectRef, value).rejectedWith[InvalidViewReferences]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, deprecatedProjectRef, source).rejectedWith[ProjectContextRejection]
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
        views.update(id, unknownProjectRef, 1, source).rejectedWith[ProjectContextRejection]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, deprecatedProjectRef, 1, source).rejectedWith[ProjectContextRejection]
      }
    }

    "tag a view" when {
      val tag = UserTag.unsafe("mytag")
      "using a correct revision" in {
        views.tag(viewId, projectRef, tag, 1, 2).accepted
      }

      "the view is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.deprecate(id, projectRef, 1).accepted
        views.tag(id, projectRef, tag, 1, 2).accepted
      }
    }

    "fail to tag a view" when {
      val tag = UserTag.unsafe("mytag")
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.tag(viewId, projectRef, tag, 1, 100).rejectedWith[IncorrectRev]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, projectRef, tag, 1, 2).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, unknownProjectRef, tag, 1, 2).rejectedWith[ProjectContextRejection]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, deprecatedProjectRef, tag, 1, 2).rejectedWith[ProjectContextRejection]
      }
    }

    "deprecate a view" when {
      "using the correct revision" in {
        views.deprecate(viewId, projectRef, 3).accepted
      }
    }

    "fail to deprecate a view" when {
      "the view is already deprecated" in {
        views.deprecate(viewId, projectRef, 4).rejectedWith[ViewIsDeprecated]
      }
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.deprecate(viewId, projectRef, 100).rejectedWith[IncorrectRev]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, projectRef, 2).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, unknownProjectRef, 2).rejectedWith[ProjectContextRejection]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, deprecatedProjectRef, 2).rejectedWith[ProjectContextRejection]
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
      "a tag is provided" in {
        val tag           = UserTag.unsafe("mytag")
        val id            = iri"http://localhost/${genString()}"
        val source        = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        val updatedSource = json"""{"@type": "ElasticSearchView", "resourceTag": "mytag", "mapping": $mapping}"""
        views.update(id, projectRef, 1, updatedSource).accepted
        views.tag(id, projectRef, tag, 1, 2).accepted
        views.fetch(IdSegmentRef(id, tag), projectRef).accepted shouldEqual resourceFor(
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
      "the tag does not exist" in {
        val tag    = UserTag.unsafe("mytag")
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.fetch(IdSegmentRef(id, tag), projectRef).rejectedWith[TagNotFound]
      }
    }

    "list views" when {
      val id              = iri"http://localhost/${genString()}"
      val idDeprecated    = iri"http://localhost/${genString()}"
      val aggregateId     = iri"http://localhost/${genString()}"
      val source          = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
      val aggregateSource = json"""
            {"@type": "AggregateElasticSearchView",
             "views": [
               {
                 "project": ${listProject.ref.toString},
                 "viewId": $id
               }
             ]}"""
      "there are no specific filters" in {
        views.create(id, listProject.ref, source).accepted
        views.create(idDeprecated, listProject.ref, source).accepted
        views.deprecate(idDeprecated, listProject.ref, 1).accepted
        views.create(aggregateId, listProject.ref, aggregateSource).accepted
        val params = ElasticSearchViewSearchParams(project = Some(listProject.ref), filter = _ => UIO.pure(true))
        views.list(Pagination.OnePage, params, Ordering.by(_.createdAt)).accepted.total shouldEqual 3
      }
      "only deprecated views are selected" in {
        val params = ElasticSearchViewSearchParams(
          project = Some(listProject.ref),
          deprecated = Some(true),
          filter = _ => UIO.pure(true)
        )
        views.list(Pagination.OnePage, params, Ordering.by(_.createdAt)).accepted.total shouldEqual 1
      }
      "only AggregateElasticSearchViews are selected" in {
        val params = ElasticSearchViewSearchParams(
          project = Some(listProject.ref),
          types = Set(AggregateElasticSearch.tpe),
          filter = _ => UIO.pure(true)
        )
        views.list(Pagination.OnePage, params, Ordering.by(_.createdAt)).accepted.total shouldEqual 1
      }
    }
  }
}
