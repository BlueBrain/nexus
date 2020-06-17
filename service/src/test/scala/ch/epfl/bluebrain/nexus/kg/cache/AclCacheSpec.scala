package ch.epfl.bluebrain.nexus.kg.cache

import akka.testkit._
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlList, AccessControlLists, Permission}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mockito.IdiomaticMockito
import org.scalatest.Inspectors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class AclCacheSpec
    extends ActorSystemFixture("AclCacheSpec", true)
    with Matchers
    with Inspectors
    with ScalaFutures
    with TestHelper
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  private val client: IamClient[Task]       = mock[IamClient[Task]]
  implicit private val iamConfig: IamConfig =
    IamConfig(url"http://base.com", url"http://base.com", "v1", None, 1.second)
  implicit private val appConfig            = Settings(system).appConfig.copy(iam = iamConfig)

  override val write = Permission.unsafe("resources/write")
  val user           = User("mySub", "myRealm")
  val group          = User("myGroup", "myRealm")

  private val cache = AclsCache[Task](client)

  "AclsCache" should {

    "replace acls" in {
      val aclResSlashFirst = resourceAcls(AccessControlList(group -> Set(read)))

      cache.get(/).runToFuture.futureValue shouldEqual None
      cache.replace(/, aclResSlashFirst).runToFuture.futureValue
      cache.get(/).runToFuture.futureValue shouldEqual Some(aclResSlashFirst)

      val aclResSlash = resourceAcls(AccessControlList(group -> Set(read))).copy(rev = 2L)
      cache.replace(/, aclResSlash).runToFuture.futureValue
      cache.get(/).runToFuture.futureValue shouldEqual Some(aclResSlash)

      val aclResSegment = resourceAcls(AccessControlList(Anonymous -> Set(read, write)))
      cache.replace("some" / "path", aclResSegment).runToFuture.futureValue
      cache.get("some" / "path").runToFuture.futureValue shouldEqual Some(aclResSegment)
      cache.get(/).runToFuture.futureValue shouldEqual Some(aclResSlash)
      cache.list.runToFuture.futureValue shouldEqual AccessControlLists(
        /               -> aclResSlash,
        "some" / "path" -> aclResSegment
      )
    }

    "append acls" in {
      val initial = resourceAcls(AccessControlList(group -> Set(read))).copy(createdBy = user)
      val path    = genString() / genString()
      cache.replace(path, initial).runToFuture.futureValue

      val append = resourceAcls(AccessControlList(user -> Set(read), group -> Set(write))).copy(rev = 2L)
      cache.append(path, append).runToFuture.futureValue
      cache.get(path).runToFuture.futureValue shouldEqual
        Some(
          append.copy(
            value = AccessControlList(user -> Set(read), group -> Set(read, write)),
            createdBy = initial.createdBy,
            createdAt = initial.createdAt
          )
        )
    }

    "subtract acls" in {
      val initial = resourceAcls(AccessControlList(group -> Set(write), user -> Set(read))).copy(createdBy = user)
      val path    = genString() / genString()
      cache.replace(path, initial).runToFuture.futureValue

      val subtract = resourceAcls(AccessControlList(user -> Set(read))).copy(rev = 2L)
      cache.subtract(path, subtract).runToFuture.futureValue
      cache.get(path).runToFuture.futureValue shouldEqual
        Some(
          subtract.copy(
            value = AccessControlList(group -> Set(write)),
            createdBy = initial.createdBy,
            createdAt = initial.createdAt
          )
        )

      val subtract2 = resourceAcls(AccessControlList(group -> Set(write))).copy(rev = 3L)
      cache.subtract(path, subtract2).runToFuture.futureValue
      cache.get(path).runToFuture.futureValue shouldEqual
        Some(
          subtract2.copy(value = AccessControlList.empty, createdBy = initial.createdBy, createdAt = initial.createdAt)
        )
    }

    "delete acls" in {
      val initial = resourceAcls(AccessControlList(group -> Set(write), user -> Set(read))).copy(createdBy = user)
      val path    = genString() / genString()
      cache.replace(path, initial).runToFuture.futureValue
      cache.remove(path).runToFuture.futureValue
      cache.get(path).runToFuture.futureValue shouldEqual None
    }
  }
}
