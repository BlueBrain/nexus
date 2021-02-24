package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DifferentElasticSearchViewType, IncorrectRev, InvalidViewReference, PermissionIsNotDefined, RevisionNotFound, TagNotFound, ViewAlreadyExists, ViewIsDeprecated, ViewNotFound, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.AggregateElasticSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{elasticsearch => elasticsearchContext}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{query => queryPermissions}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import io.circe.literal._
import monix.bio.UIO
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
    with ConfigFixtures {

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata    -> jsonContentOf("/contexts/metadata.json"),
      elasticsearchContext -> jsonContentOf("/contexts/elasticsearch.json")
    )

  "An ElasticSearchViews" should {

    val config =
      ElasticSearchViewsConfig(
        aggregate,
        keyValueStore,
        pagination,
        cacheIndexing,
        externalIndexing,
        keyValueStore
      )

    val eventLog: EventLog[Envelope[ElasticSearchViewEvent]] =
      EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors.accepted

    val org                      = Label.unsafe("org")
    val orgDeprecated            = Label.unsafe("org-deprecated")
    val apiMappings              = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
    val base                     = nxv.base
    val project                  = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
    val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
    val listProject              = ProjectGen.project("org", "list", base = base, mappings = apiMappings)

    val projectRef                  = project.ref
    val deprecatedProjectRef        = deprecatedProject.ref
    val projectWithDeprecatedOrgRef = projectWithDeprecatedOrg.ref
    val unknownProjectRef           = ProjectRef(org, Label.unsafe("xxx"))

    val projects = ProjectSetup
      .init(
        orgsToCreate = org :: orgDeprecated :: Nil,
        projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: listProject :: Nil,
        projectsToDeprecate = deprecatedProject.ref :: Nil,
        organizationsToDeprecate = orgDeprecated :: Nil
      )
      .map(_._2)
      .accepted

    val permissions = PermissionsDummy(Set(queryPermissions)).accepted

    val views = ElasticSearchViews(
      config,
      eventLog,
      projects,
      permissions,
      (_, _) => UIO.unit
    ).accepted

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
      }"""

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
      }"""

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
        .toResource(project.apiMappings, project.base)
        .value

    val viewId = iri"http://localhost/indexing"
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
          value = IndexingElasticSearchViewValue(mapping = mapping, settings = Some(settings)),
          source = source
        )
        views.create(projectRef, source).accepted shouldEqual expected
      }
      "using an IndexingElasticSearchViewValue" in {
        val id    = iri"http://localhost/${genString()}"
        val value = IndexingElasticSearchViewValue(
          resourceSchemas = Set(iri"http://localhost/schema"),
          resourceTypes = Set(iri"http://localhost/type"),
          resourceTag = Some(TagLabel.unsafe("tag")),
          sourceAsText = false,
          includeMetadata = false,
          includeDeprecated = false,
          mapping = mapping,
          settings = None,
          permission = queryPermissions
        )
        views.create(IriSegment(id), projectRef, value).accepted
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
        views.create(IriSegment(id), projectRef, value).accepted
      }
    }
    "reject creating a view" when {
      "a view already exists" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(projectRef, source).rejectedWith[ViewAlreadyExists]
        views.create(IriSegment(viewId), projectRef, source).rejectedWith[ViewAlreadyExists]
      }
      "the permission is not defined" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping, "permission": "not/exists"}"""
        views.create(IriSegment(id), projectRef, source).rejectedWith[PermissionIsNotDefined]
      }
      "the referenced view does not exist" in {
        val id           = iri"http://localhost/${genString()}"
        val referencedId = iri"http://localhost/${genString()}"
        val value        = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(projectRef, referencedId)))
        views.create(IriSegment(id), projectRef, value).rejectedWith[InvalidViewReference]
      }
      "the referenced project does not exist" in {
        val id    = iri"http://localhost/${genString()}"
        val value = AggregateElasticSearchViewValue(NonEmptySet.of(ViewRef(unknownProjectRef, viewId)))
        views.create(IriSegment(id), projectRef, value).rejectedWith[InvalidViewReference]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), deprecatedProjectRef, source).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectWithDeprecatedOrgRef, source).rejectedWith[WrappedProjectRejection]
      }
    }

    "update a view" when {
      "using the minimum fields for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(IriSegment(viewId), projectRef, 1L, source).accepted
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
        views.update(IriSegment(id), projectRef, 1L, source).accepted
      }
    }
    "fail to update a view" when {
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(IriSegment(viewId), projectRef, 100L, source).rejectedWith[IncorrectRev]
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
        views.update(IriSegment(id), projectRef, 100L, source).rejectedWith[IncorrectRev]
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
        views.update(IriSegment(viewId), projectRef, 2L, source).rejectedWith[DifferentElasticSearchViewType]
      }
      "the view is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        views.deprecate(IriSegment(id), projectRef, 1L).accepted
        views.update(IriSegment(id), projectRef, 2L, source).rejectedWith[ViewIsDeprecated]
      }
      "the target view is not found" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(IriSegment(id), projectRef, 1L, source).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(IriSegment(id), unknownProjectRef, 1L, source).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(IriSegment(id), deprecatedProjectRef, 1L, source).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.update(IriSegment(id), projectWithDeprecatedOrgRef, 1L, source).rejectedWith[WrappedProjectRejection]
      }
    }

    "tag a view" when {
      val tag = TagLabel.unsafe("mytag")
      "using a correct revision" in {
        views.tag(IriSegment(viewId), projectRef, tag, 1L, 2L).accepted
      }
    }

    "fail to tag a view" when {
      val tag = TagLabel.unsafe("mytag")
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.tag(IriSegment(viewId), projectRef, tag, 1L, 100L).rejectedWith[IncorrectRev]
      }
      "the view is deprecated" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        views.deprecate(IriSegment(id), projectRef, 1L).accepted
        views.tag(IriSegment(id), projectRef, tag, 1L, 2L).rejectedWith[ViewIsDeprecated]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(IriSegment(id), projectRef, tag, 1L, 2L).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(IriSegment(id), unknownProjectRef, tag, 1L, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(IriSegment(id), deprecatedProjectRef, tag, 1L, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.tag(IriSegment(id), projectWithDeprecatedOrgRef, tag, 1L, 2L).rejectedWith[WrappedProjectRejection]
      }
    }

    "deprecate a view" when {
      "using the correct revision" in {
        views.deprecate(IriSegment(viewId), projectRef, 3L).accepted
      }
    }

    "fail to deprecate a view" when {
      "the view is already deprecated" in {
        views.deprecate(IriSegment(viewId), projectRef, 4L).rejectedWith[ViewIsDeprecated]
      }
      "providing an incorrect revision for an IndexingElasticSearchViewValue" in {
        views.deprecate(IriSegment(viewId), projectRef, 100L).rejectedWith[IncorrectRev]
      }
      "the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(IriSegment(id), projectRef, 2L).rejectedWith[ViewNotFound]
      }
      "the project of the target view is not found" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(IriSegment(id), unknownProjectRef, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(IriSegment(id), deprecatedProjectRef, 2L).rejectedWith[WrappedProjectRejection]
      }
      "the referenced project parent organization is deprecated" in {
        val id = iri"http://localhost/${genString()}"
        views.deprecate(IriSegment(id), projectWithDeprecatedOrgRef, 2L).rejectedWith[WrappedProjectRejection]
      }
    }

    "fetch a view by id" when {
      "no rev nor tag is provided" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        views.fetch(IriSegment(id), projectRef).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            sourceAsText = true,
            includeMetadata = true,
            includeDeprecated = true,
            mapping = mapping,
            permission = queryPermissions
          ),
          source = source
        )
      }
      "a rev is provided" in {
        val id            = iri"http://localhost/${genString()}"
        val source        = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        val updatedSource = json"""{"@type": "ElasticSearchView", "resourceTag": "mytag", "mapping": $mapping}"""
        views.update(IriSegment(id), projectRef, 1L, updatedSource).accepted
        views.fetchAt(IriSegment(id), projectRef, 1L).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            sourceAsText = true,
            includeMetadata = true,
            includeDeprecated = true,
            mapping = mapping,
            permission = queryPermissions
          ),
          source = source
        )
      }
      "a tag is provided" in {
        val tag           = TagLabel.unsafe("mytag")
        val id            = iri"http://localhost/${genString()}"
        val source        = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        val updatedSource = json"""{"@type": "ElasticSearchView", "resourceTag": "mytag", "mapping": $mapping}"""
        views.update(IriSegment(id), projectRef, 1L, updatedSource).accepted
        views.tag(IriSegment(id), projectRef, tag, 1L, 2L).accepted
        views.fetchBy(IriSegment(id), projectRef, tag).accepted shouldEqual resourceFor(
          id = id,
          value = IndexingElasticSearchViewValue(
            resourceSchemas = Set.empty,
            resourceTypes = Set.empty,
            resourceTag = None,
            sourceAsText = true,
            includeMetadata = true,
            includeDeprecated = true,
            mapping = mapping,
            permission = queryPermissions
          ),
          source = source
        )
      }
    }

    "fail to fetch a view" when {
      "the view does not exist" in {
        val id = iri"http://localhost/${genString()}"
        views.fetch(IriSegment(id), projectRef).rejectedWith[ViewNotFound]
      }
      "the revision does not exist" in {
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        views.fetchAt(IriSegment(id), projectRef, 2L).rejectedWith[RevisionNotFound]
      }
      "the tag does not exist" in {
        val tag    = TagLabel.unsafe("mytag")
        val id     = iri"http://localhost/${genString()}"
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        views.create(IriSegment(id), projectRef, source).accepted
        views.fetchBy(IriSegment(id), projectRef, tag).rejectedWith[TagNotFound]
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
        views.create(IriSegment(id), listProject.ref, source).accepted
        views.create(IriSegment(idDeprecated), listProject.ref, source).accepted
        views.deprecate(IriSegment(idDeprecated), listProject.ref, 1L).accepted
        views.create(IriSegment(aggregateId), listProject.ref, aggregateSource).accepted
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
      // 27 events so far
      "no offset is provided" in {
        val list = views
          .events(NoOffset)
          .take(27)
          .map(envelope => (envelope.event.id, envelope.eventType))
          .compile
          .toVector
          .accepted

        list.size shouldEqual 27

        val (id, tpe) = list(1)
        id shouldEqual viewId
        tpe shouldEqual "ElasticSearchViewCreated"
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

  }

}
