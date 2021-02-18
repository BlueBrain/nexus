package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.elasticsearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultPermission, defaultViewId, ElasticSearchViewEvent}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class ElasticSearchScopeInitializationSpec
    extends AbstractDBSpec
    with AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with OptionValues
    with TestHelpers
    with ConfigFixtures {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org      = Label.unsafe("org")
  private val am       = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schemaorg.Person))
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val mapping  = jsonContentOf("/defaults/default-mapping.json")
  private val settings = jsonContentOf("/defaults/default-settings.json")

  val views: ElasticSearchViews = {
    implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

    implicit val res: RemoteContextResolution =
      RemoteContextResolution.fixed(
        contexts.metadata -> jsonContentOf("/contexts/metadata.json"),
        elasticsearch     -> jsonContentOf("/contexts/elasticsearch.json")
      )

    val config =
      ElasticSearchViewsConfig(
        aggregate,
        keyValueStore,
        pagination,
        cacheIndexing,
        externalIndexing,
        processor,
        keyValueStore
      )

    (for {
      permissions <- PermissionsDummy(Set(defaultPermission))
      eventLog    <- EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors
      (_, p)      <- ProjectSetup.init(List(org), List(project))
      views       <- ElasticSearchViews(config, eventLog, p, permissions, (_, _) => UIO.unit)
    } yield views).accepted
  }

  "An ElasticSearchScopeInitialization" should {
    val init = new ElasticSearchScopeInitialization(views, sa)

    "create a default ElasticSearchView on a newly created project" in {
      views.fetch(IriSegment(defaultViewId), project.ref).rejectedWith[ViewNotFound]
      init.onProjectCreation(project, bob).accepted
      val resource = views.fetch(IriSegment(defaultViewId), project.ref).accepted
      resource.value match {
        case v: IndexingElasticSearchView  =>
          v.resourceSchemas shouldBe empty
          v.resourceTypes shouldBe empty
          v.resourceTag shouldEqual None
          v.sourceAsText shouldEqual true
          v.includeMetadata shouldEqual true
          v.includeDeprecated shouldEqual true
          v.mapping shouldEqual mapping
          v.settings shouldEqual Some(settings)
          v.permission shouldEqual defaultPermission
        case _: AggregateElasticSearchView => fail("Expected an IndexingElasticSearchView to be created")
      }
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a default ElasticSearchView if one already exists" in {
      views.fetch(IriSegment(defaultViewId), project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project, bob).accepted
      views.fetch(IriSegment(defaultViewId), project.ref).accepted.rev shouldEqual 1L
    }

  }
}
