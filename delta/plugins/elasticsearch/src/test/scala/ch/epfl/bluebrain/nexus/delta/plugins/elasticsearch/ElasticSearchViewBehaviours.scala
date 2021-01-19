package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.persistence.query.{EventEnvelope, Offset}
import akka.util.Timeout
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.{AggregateConfig, ElasticSearchViewConfig, IndexingConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DifferentElasticSearchViewType, IncorrectRev, InvalidViewReference, PermissionIsNotDefined, ViewAlreadyExists, ViewIsDeprecated, ViewNotFound, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ElasticSearchViewResource, ElasticSearchViewValue, ViewRef}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import ch.epfl.bluebrain.nexus.sourcing.{EventLog, SnapshotStrategyConfig}
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
import scala.concurrent.duration._

trait ElasticSearchViewBehaviours {
  this: AbstractDbSpec
    with AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with OptionValues
    with TestHelpers =>

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata             -> jsonContentOf("/contexts/metadata.json"),
      ElasticSearchViews.contextIri -> jsonContentOf("/contexts/elasticsearchviews.json")
    )

  "An ElasticSearchViews" should {
    val config = ElasticSearchViewConfig(
      aggregate = AggregateConfig(
        stopStrategy = StopStrategyConfig(None, None),
        snapshotStrategy = SnapshotStrategyConfig(None, None, None).value,
        processor = EventSourceProcessorConfig(
          askTimeout = Timeout(5.seconds),
          evaluationMaxDuration = 3.second,
          evaluationExecutionContext = system.executionContext,
          stashSize = 100
        )
      ),
      keyValueStore = KeyValueStoreConfig(
        askTimeout = 5.seconds,
        consistencyTimeout = 2.seconds,
        RetryStrategyConfig.AlwaysGiveUp
      ),
      pagination = PaginationConfig(
        defaultSize = 30,
        sizeLimit = 100,
        fromLimit = 10000
      ),
      indexing = IndexingConfig(
        concurrency = 1,
        retry = RetryStrategyConfig.ConstantStrategyConfig(1.second, 10)
      )
    )

    val eventLog: EventLog[Envelope[ElasticSearchViewEvent]] =
      EventLog
        .postgresEventLog({
          case ee @ EventEnvelope(offset: Offset, persistenceId, sequenceNr, value: ElasticSearchViewEvent) =>
            UIO.pure(
              Some(Envelope(value, ClassUtils.simpleName(value), offset, persistenceId, sequenceNr, ee.timestamp))
            )
          case ee                                                                                           =>
            UIO.terminate(
              new IllegalArgumentException(
                s"Failed to match envelope value '${ee.event}' to class '${classOf[ElasticSearchViewEvent].getCanonicalName}'"
              )
            )
        })
        .accepted

    val org                      = Label.unsafe("org")
    val orgDeprecated            = Label.unsafe("org-deprecated")
    val apiMappings              = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
    val base                     = nxv.base
    val project                  = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
    val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")

    val projectRef        = project.ref
//    val deprecatedProjectRef = deprecatedProject.ref
    val unknownProjectRef = ProjectRef(org, Label.unsafe("xxx"))

    val projects = ProjectSetup
      .init(
        orgsToCreate = org :: orgDeprecated :: Nil,
        projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
        projectsToDeprecate = deprecatedProject.ref :: Nil,
        organizationsToDeprecate = orgDeprecated :: Nil
      )
      .map(_._2)
      .accepted

    val queryPermission = Permission.unsafe("views/query")

    val permissions = PermissionsDummy(Set(queryPermission)).accepted

    val views = ElasticSearchViews(
      config,
      eventLog,
      projects,
      permissions
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
    ): ElasticSearchViewResource =
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
        val source   = json"""{"@id": $viewId, "@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected = resourceFor(
          id = viewId,
          value = IndexingElasticSearchViewValue(mapping = mapping),
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
          permission = queryPermission
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
    }
  }

}
