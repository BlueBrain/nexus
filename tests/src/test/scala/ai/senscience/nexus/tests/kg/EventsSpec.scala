package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.Identity.events.BugsBunny
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.iam.types.Permission.{Events, Organizations, Resources}
import ai.senscience.nexus.tests.kg.files.model.FileInput
import ai.senscience.nexus.tests.resources.SimpleResource
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import io.circe.Json

class EventsSpec extends BaseIntegrationSpec {

  private val orgId                          = genId()
  private val orgId2                         = genId()
  private val projId                         = genId()
  private val id                             = s"$orgId/$projId"
  private val id2                            = s"$orgId2/$projId"
  private val initialEventId: Option[String] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val permissions = Set(Organizations.Create, Events.Read, Resources.Read)
    val setup       = for {
      _ <- aclDsl.addPermissions("/", BugsBunny, permissions)
      _ <- adminDsl.createOrganization(orgId, orgId, BugsBunny)
      _ <- adminDsl.createProjectWithName(orgId, projId, name = id, BugsBunny)
      _ <- adminDsl.createOrganization(orgId2, orgId2, BugsBunny)
      _ <- adminDsl.createProjectWithName(orgId2, projId, name = id2, BugsBunny)
    } yield ()
    setup.accepted
  }

  "fetching events" should {

    "add events to project" in {
      // Created event
      val resourceId = "https://dev.nexus.test.com/simplified-resource/1"
      val payload    = SimpleResource.sourcePayload(resourceId, 3).accepted

      val fileContent        = """{ "this": ["is", "a", "test", "attachment"]}"""
      val updatedFileContent = """{ "this": ["is", "a", "test", "attachment", "2"] }"""

      implicit val identity: Identity = BugsBunny

      for {
        // ResourceCreated event
        _          <- deltaClient.put[Json](s"/resources/$id/_/test-resource:1", payload, BugsBunny) { expectCreated }
        _          <- deltaClient.put[Json](s"/resources/$id2/_/test-resource:1", payload, BugsBunny) { expectCreated }
        // ResourceUpdated event
        payload    <- SimpleResource.sourcePayload(resourceId, 5)
        _          <- deltaClient.put[Json](s"/resources/$id/_/test-resource:1?rev=1", payload, BugsBunny) { expectOk }
        // ResourceTagAdded event
        tagPayload  = tag("v1.0.0", 1)
        _          <- deltaClient.post[Json](s"/resources/$id/_/test-resource:1/tags?rev=2", tagPayload, BugsBunny) {
                        expectCreated
                      }
        // ResourceDeprecated event
        _          <- deltaClient.delete[Json](s"/resources/$id/_/test-resource:1?rev=3", BugsBunny) { expectOk }
        // FileCreated event
        fileCreated = FileInput("attachment.json", "attachment.json", `application/json`, fileContent)
        _          <- deltaClient.uploadFile(id, None, fileCreated, None) { expectCreated }
        // FileUpdated event
        fileUpdated = FileInput("attachment.json", "attachment.json", `application/json`, updatedFileContent)
        _          <- deltaClient.uploadFile(id, None, fileUpdated, Some(1)) { expectOk }
      } yield succeed
    }

    "fetch resource events filtered by project" in eventually {
      deltaClient.sseEvents(s"/resources/$id/events", BugsBunny, initialEventId, take = 6L) { sses =>
        sses.size shouldEqual 6
        sses.flatMap(_._1) should contain theSameElementsInOrderAs List(
          "ResourceCreated",
          "ResourceUpdated",
          "ResourceTagAdded",
          "ResourceDeprecated",
          "FileCreated",
          "FileUpdated"
        )
        val json = Json.arr(sses.flatMap(_._2.map(events.filterFields))*)
        json shouldEqual expectedEvents("kg/events/events.json", orgId, projId)
      }
    }

    "fetch resource events filtered by organization 1" in {
      deltaClient.sseEvents(s"/resources/$orgId/events", BugsBunny, initialEventId, take = 6L) { sses =>
        sses.size shouldEqual 6
        sses.flatMap(_._1) should contain theSameElementsInOrderAs List(
          "ResourceCreated",
          "ResourceUpdated",
          "ResourceTagAdded",
          "ResourceDeprecated",
          "FileCreated",
          "FileUpdated"
        )
        val json = Json.arr(sses.flatMap(_._2.map(events.filterFields))*)
        json shouldEqual expectedEvents("kg/events/events.json", orgId, projId)
      }
    }

    "fetch resource events filtered by organization 2" in {
      deltaClient.sseEvents(s"/resources/$orgId2/events", BugsBunny, initialEventId, take = 1L) { sses =>
        sses.size shouldEqual 1
        sses.flatMap(_._1) should contain theSameElementsInOrderAs List("ResourceCreated")
        val json = Json.arr(sses.flatMap(_._2.map(events.filterFields))*)
        json shouldEqual expectedEvents("kg/events/events2.json", orgId2, projId)
      }
    }
  }

  private def expectedEvents(file: String, org: String, proj: String) =
    jsonContentOf(
      file,
      replacements(
        BugsBunny,
        "resources" -> s"${config.deltaUri}/resources/$id",
        "project"   -> s"$org/$proj"
      )*
    )
}
