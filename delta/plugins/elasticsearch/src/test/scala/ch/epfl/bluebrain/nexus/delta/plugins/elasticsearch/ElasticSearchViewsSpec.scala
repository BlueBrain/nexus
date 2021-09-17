package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DifferentElasticSearchViewType, IncorrectRev, InvalidViewReference, PermissionIsNotDefined, ResourceAlreadyExists, RevisionNotFound, TagNotFound, ViewIsDeprecated, ViewNotFound, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.AggregateElasticSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{query => queryPermissions}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import io.circe.literal._
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ElasticSearchViewsSpec
    extends AbstractDBSpec
    with AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with OptionValues
    with TestHelpers
    with ConfigFixtures
    with Fixtures {

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val defaultEsMapping  = defaultElasticsearchMapping.accepted
  private val defaultEsSettings = defaultElasticsearchSettings.accepted

  "An ElasticSearchViews" should {

    val org                      = Label.unsafe("org")
    val orgDeprecated            = Label.unsafe("org-deprecated")
    val apiMappings              = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
    val base                     = nxv.base
    val project                  = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val project2                 = ProjectGen.project("org", "proj2", base = base, mappings = apiMappings)
    val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
    val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
    val listProject              = ProjectGen.project("org", "list", base = base, mappings = apiMappings)

    val projectRef                  = project.ref
    val deprecatedProjectRef        = deprecatedProject.ref
    val projectWithDeprecatedOrgRef = projectWithDeprecatedOrg.ref
    val unknownProjectRef           = ProjectRef(org, Label.unsafe("xxx"))

    val (orgs, projects) = ProjectSetup
      .init(
        orgsToCreate = org :: orgDeprecated :: Nil,
        projectsToCreate = project :: project2 :: deprecatedProject :: projectWithDeprecatedOrg :: listProject :: Nil,
        projectsToDeprecate = deprecatedProject.ref :: Nil,
        organizationsToDeprecate = orgDeprecated :: Nil
      )
      .accepted

    val views = ElasticSearchViewsSetup.init(orgs, projects, queryPermissions)

    val mapping =
      json"""{
        "dynamic": false,
        "properties": {
          "@id": {
            "type": "keyword"
          },
          "@type": {
            "type": "keyword"
          },
          "name": {
            "type": "keyword"
          },
          "number": {
            "type": "long"
          },
          "bool": {
            "type": "boolean"
          }
        }
      }""".asObject.value

    val settings =
      json"""{
        "analysis": {
          "analyzer": {
            "nexus": {
              "type": "custom",
              "tokenizer": "classic",
              "filter": [
                "my_multiplexer"
              ]
            }
          }
        }
      }""".asObject.value

    def currentStateFor(
        id: Iri,
        project: Project,
        uuid: UUID,
        rev: Long,
        deprecated: Boolean,
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject,
        value: ElasticSearchViewValue,
        source: Json,
        tags: Map[TagLabel, Long]
    ): Current =
      Current(
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
        rev: Long = 1L,
        deprecated: Boolean = false,
        createdAt: Instant = Instant.EPOCH,
        createdBy: Subject = alice.subject,
        updatedAt: Instant = Instant.EPOCH,
        updatedBy: Subject = alice.subject,
        value: ElasticSearchViewValue,
        source: Json,
        tags: Map[TagLabel, Long] = Map.empty
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
      )
        .toResource(project.apiMappings, project.base, defaultEsMapping, defaultEsSettings)
        .value

    val viewId  = iri"http://localhost/indexing"
    val viewId2 = iri"http://localhost/${genString()}"
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
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            mapping = Some(mapping),
            settings = Some(settings),
            includeMetadata = false,
            includeDeprecated = false,
            sourceAsText = false,
            permission = queryPermissions
          ),
          source = source
        )
        views.create(projectRef, source).accepted shouldEqual expected
      }
      "using an IndexingElasticSearchViewValue" in {
        val value = IndexingElasticSearchViewValue(
          resourceSchemas = Set(iri"http://localhost/schema"),
          resourceTypes = Set(iri"http://localhost/type"),
          resourceTag = Some(TagLabel.unsafe("tag")),
          sourceAsText = false,
          includeMetadata = false,
          includeDeprecated = false,
          mapping = Some(mapping),
          settings = None,
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
      "the permission is not defined" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping, "permission": "not/exists"}"""
        views.create(id, projectRef, source).rejectedWith[PermissionIsNotDefined]
      }
      "the referenced view does not exist" in {
        val id           = iri"http://localhost/${genString()}"
        val referencedId = iri"http://localhost/${genString()}"
        val value        = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(projectRef, referencedId)))
        views.create(id, projectRef, value).rejectedWith[InvalidViewReference]
      }
      "the referenced project does not exist" in {
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(unknownProjectRef, viewId)))
        views.create(id, projectRef, value).rejectedWith[InvalidViewReference]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, deprecatedProjectRef, source).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectWithDeprecatedOrgRef, source).rejectedWith[WrappedProjectRejection]
      }
    }

    "update a view" when {
      "using the minimum fields for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(viewId, projectRef, 1L, source).accepted
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
        views.update(id, projectRef, 1L, source).accepted
      }
    }
    "fail to update a view" when {
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(viewId, projectRef, 100L, source).rejectedWith[IncorrectRev]
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
        views.update(id, projectRef, 100L, source).rejectedWith[IncorrectRev]
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
        views.update(viewId, projectRef, 2L, source).rejectedWith[DifferentElasticSearchViewType]
      }
      "the view is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.deprecate(id, projectRef, 1L).accepted
        views.update(id, projectRef, 2L, source).rejectedWith[ViewIsDeprecated]
      }
      "the target view is not found" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, projectRef, 1L, source).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, unknownProjectRef, 1L, source).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, deprecatedProjectRef, 1L, source).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(id, projectWithDeprecatedOrgRef, 1L, source).rejectedWith[WrappedProjectRejection]
      }
    }

    "tag a view" when {
      val tag = TagLabel.unsafe("mytag")
      "using a correct revision" in {
        views.tag(viewId, projectRef, tag, 1L, 2L).accepted
      }
    }

    "fail to tag a view" when {
      val tag = TagLabel.unsafe("mytag")
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.tag(viewId, projectRef, tag, 1L, 100L).rejectedWith[IncorrectRev]
      }
      "the view is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        views.deprecate(id, projectRef, 1L).accepted
        views.tag(id, projectRef, tag, 1L, 2L).rejectedWith[ViewIsDeprecated]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, projectRef, tag, 1L, 2L).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, unknownProjectRef, tag, 1L, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, deprecatedProjectRef, tag, 1L, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(id, projectWithDeprecatedOrgRef, tag, 1L, 2L).rejectedWith[WrappedProjectRejection]
      }
    }

    "deprecate a view" when {
      "using the correct revision" in {
        views.deprecate(viewId, projectRef, 3L).accepted
      }
    }

    "fail to deprecate a view" when {
      "the view is already deprecated" in {
        views.deprecate(viewId, projectRef, 4L).rejectedWith[ViewIsDeprecated]
      }
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.deprecate(viewId, projectRef, 100L).rejectedWith[IncorrectRev]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, projectRef, 2L).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, unknownProjectRef, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, deprecatedProjectRef, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(id, projectWithDeprecatedOrgRef, 2L).rejectedWith[WrappedProjectRejection]
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
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            sourceAsText = false,
            includeMetadata = false,
            includeDeprecated = false,
            mapping = Some(mapping),
            settings = None,
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
        views.update(id, projectRef, 1L, updatedSource).accepted
        views.fetch(IdSegmentRef(id, 1), projectRef).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            sourceAsText = false,
            includeMetadata = false,
            includeDeprecated = false,
            mapping = Some(mapping),
            settings = None,
            permission = queryPermissions
          ),
          source = source
        )
      }
      "a tag is provided" in {
        val tag           = TagLabel.unsafe("mytag")
        val id            = iri"http://localhost/${genString()}"
        val source        = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(id, projectRef, source).accepted
        val updatedSource = json"""{"@type": "ElasticSearchView", "resourceTag": "mytag", "mapping": $mapping}"""
        views.update(id, projectRef, 1L, updatedSource).accepted
        views.tag(id, projectRef, tag, 1L, 2L).accepted
        views.fetch(IdSegmentRef(id, tag), projectRef).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            sourceAsText = false,
            includeMetadata = false,
            includeDeprecated = false,
            mapping = Some(mapping),
            settings = None,
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
        val tag    = TagLabel.unsafe("mytag")
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
        views.deprecate(idDeprecated, listProject.ref, 1L).accepted
        views.create(aggregateId, listProject.ref, aggregateSource).accepted
        val params = ElasticSearchViewSearchParams(project = Some(listProject.ref), filter = _ => true)
        views.list(Pagination.OnePage, params, Ordering.by(_.createdAt)).accepted.total shouldEqual 3
      }
      "only deprecated views are selected" in {
        val params = ElasticSearchViewSearchParams(
          project = Some(listProject.ref),
          deprecated = Some(true),
          filter = _ => true
        )
        views.list(Pagination.OnePage, params, Ordering.by(_.createdAt)).accepted.total shouldEqual 1
      }
      "only AggregateElasticSearchViews are selected" in {
        val params = ElasticSearchViewSearchParams(
          project = Some(listProject.ref),
          types = Set(AggregateElasticSearch.tpe),
          filter = _ => true
        )
        views.list(Pagination.OnePage, params, Ordering.by(_.createdAt)).accepted.total shouldEqual 1
      }
    }

    "list events" when {
      // 27 events so far, 23 of project 'org/proj' and 4 of project 'org/list'
      "no offset is provided" in {
        val streams = List(
          views.events(NoOffset)                      -> 27,
          views.events(org, NoOffset).accepted        -> 27,
          views.events(projectRef, NoOffset).accepted -> 23
        )
        forAll(streams) { case (stream, size) =>
          val list = stream
            .take(size.toLong)
            .map(envelope => (envelope.event.id, envelope.eventType))
            .compile
            .toVector
            .accepted

          list.size shouldEqual size

          val (id, tpe) = list(1)
          id shouldEqual viewId
          tpe shouldEqual "ElasticSearchViewCreated"
        }
      }

      "an offset is provided" in {
        val list = views
          .events(Sequence(0))
          .take(26)
          .map(envelope => (envelope.event.id, envelope.eventType))
          .compile
          .toVector
          .accepted

        list.size shouldEqual 26

        val (id, tpe) = list(1)
        id shouldEqual viewId
        tpe shouldEqual "ElasticSearchViewCreated"
      }
    }

    "find references" when {

      "an aggregate view point to a view in 'proj'" in {
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(projectRef, viewId2)))
        views.create(id, project2.ref, value).accepted

        ElasticSearchViews.projectReferenceFinder(views)(projectRef).accepted shouldEqual
          ProjectReferenceMap.single(project2.ref, id)
      }

    }

  }

}
