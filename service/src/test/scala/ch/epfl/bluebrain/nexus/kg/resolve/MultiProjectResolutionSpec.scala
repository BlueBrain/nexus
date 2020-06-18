package ch.epfl.bluebrain.nexus.kg.resolve

import java.time.{Clock, Instant}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import cats.data.OptionT
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.commons.test.io.IOOptionValues
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Ref.Latest
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.simpleF
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import io.circe.Json
import org.mockito.Mockito._
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class MultiProjectResolutionSpec
    extends TestKit(ActorSystem("MultiProjectResolutionSpec"))
    with AnyWordSpecLike
    with Matchers
    with IdiomaticMockito
    with BeforeAndAfter
    with EitherValues
    with TestHelper
    with OptionValues
    with BeforeAndAfterAll
    with IOOptionValues {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  private def genProjectLabel = ProjectLabel(genString(), genString())
  private def genJson: Json   = Json.obj("key" -> Json.fromString(genString()))

  implicit private val appConfig    = Settings(system).appConfig
  implicit private val clock: Clock = Clock.systemUTC

  private val repo        = mock[Repo[IO]]
  private val managePerms = Set(Permission.unsafe("resources/read"), Permission.unsafe("resources/write"))

  private val base                        = Iri.absolute("https://nexus.example.com").getOrElse(fail)
  private val resId                       = base + "some-id"
  private val (proj1Id, proj2Id, proj3Id) =
    (genUUID, genUUID, genUUID)
  private val (proj1, proj2, proj3)       = (genProjectLabel, genProjectLabel, genProjectLabel)
  private val projects                    = List(proj1Id, proj2Id, proj3Id).map(ProjectRef(_)) // we want to ensure traversal order
  private val types                       = Set(nxv.Schema.value, nxv.Resource.value)
  private val group                       = Group("bbp-ou-neuroinformatics", "ldap2")
  private val identities                  = Set[Identity](group, User("dmontero", "ldap"))
  implicit val timeout                    = Timeout(1.second)
  implicit val ec                         = system.dispatcher
  implicit val timer: Timer[IO]           = IO.timer(system.dispatcher)

  private val projectCache = ProjectCache[IO]
  val acls                 = AccessControlLists(/ -> resourceAcls(AccessControlList(group -> managePerms)))

  private val resolution = MultiProjectResolution[IO](repo, projects, types, identities, projectCache, acls)

  override def beforeAll(): Unit = {
    super.beforeAll()
    List(proj1 -> proj1Id, proj2 -> proj2Id, proj3 -> proj3Id).foreach {
      case (proj, id) =>
        val organizationUuid = genUUID
        // format: off
        val metadata = Project(genIri, proj.value, proj.organization, None, base, genIri, Map(), id, organizationUuid, 0L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
        // format: on
        projectCache.replace(metadata).ioValue
    }
  }

  before(Mockito.reset(repo))

  "A MultiProjectResolution" should {
    val (id1, id2, id3) =
      (Id(ProjectRef(proj1Id), resId), Id(ProjectRef(proj2Id), resId), Id(ProjectRef(proj3Id), resId))
    "look in all projects to resolve a resource" in {
      val value = simpleF(id3, genJson, types = types)
      repo.get(id1, None) shouldReturn OptionT.none[IO, Resource]
      repo.get(id2, None) shouldReturn OptionT.none[IO, Resource]
      repo.get(id3, None) shouldReturn OptionT.some[IO](value)

      resolution.resolve(Latest(resId)).some shouldEqual value
      verify(repo, times(1)).get(id1, None)
      verify(repo, times(1)).get(id2, None)
    }

    "filter results according to the resolvers' resource types" in {
      val value1 = simpleF(id1, genJson)
      val value2 = simpleF(id2, genJson, types = Set(nxv.Schema.value))
      val value3 = simpleF(id3, genJson, types = Set(nxv.Ontology.value))
      List(id1 -> value1, id2 -> value2, id3 -> value3).foreach {
        case (id, value) => repo.get(id, None) shouldReturn OptionT.some[IO](value)
      }

      resolution.resolve(Latest(resId)).ioValue shouldEqual Some(value2)
    }

    "filter results according to the resolvers' identities" in {
      val List(_, value2, _) = List(id1, id2, id3).map { id =>
        val value = simpleF(id, genJson, types = types)
        repo.get(id, None) shouldReturn OptionT.some[IO](value)
        value
      }
      val acl                =
        AccessControlLists(proj2.organization / proj2.value -> resourceAcls(AccessControlList(group -> managePerms)))

      val newResolution      = MultiProjectResolution[IO](repo, projects, types, identities, projectCache, acl)

      newResolution.resolve(Latest(resId)).ioValue shouldEqual Some(value2)
    }

    "return none if the resource is not found in any project" in {
      List(proj1Id, proj2Id, proj3Id).foreach { id =>
        repo.get(Id(ProjectRef(id), resId), None) shouldReturn OptionT.none[IO, Resource]
      }
      resolution.resolve(Latest(resId)).ioValue shouldEqual None
    }
  }
}
